package com.example.api_gateway_bff.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.api_gateway_bff.exception.RateLimitExceededException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * レート制限フィルター
 *
 * <p>APIへの過剰なリクエストを制限し、ブルートフォース攻撃やDDoS攻撃を軽減します。</p>
 *
 * <h3>レート制限ルール:</h3>
 * <table border="1">
 *   <tr>
 *     <th>エンドポイント</th>
 *     <th>制限</th>
 *     <th>識別方法</th>
 *     <th>目的</th>
 *   </tr>
 *   <tr>
 *     <td>/bff/auth/login</td>
 *     <td>30リクエスト/分</td>
 *     <td>IPアドレス</td>
 *     <td>ブルートフォース攻撃防止</td>
 *   </tr>
 *   <tr>
 *     <td>/api/** (認証済み)</td>
 *     <td>200リクエスト/分</td>
 *     <td>セッションID</td>
 *     <td>API乱用防止</td>
 *   </tr>
 *   <tr>
 *     <td>/api/** (未認証)</td>
 *     <td>100リクエスト/分</td>
 *     <td>IPアドレス</td>
 *     <td>DoS攻撃防止（書籍検索等の公開API保護）</td>
 *   </tr>
 * </table>
 *
 * <h3>除外エンドポイント:</h3>
 * <ul>
 *   <li>/actuator/health - 監視システムからのヘルスチェック</li>
 *   <li>/bff/login/oauth2/code/** - IdPからのコールバック</li>
 *   <li>/oauth2/authorization/** - OAuth2認証開始</li>
 *   <li>/bff/auth/logout - ログアウト（セッション無効化済み）</li>
 * </ul>
 *
 * <h3>設計方針:</h3>
 * <ul>
 *   <li><b>分散環境対応</b>: Redis + Bucket4jで複数インスタンス間で制限を共有</li>
 *   <li><b>柔軟な設定</b>: エンドポイントごとに異なるレート制限ルールを適用</li>
 *   <li><b>ユーザーフレンドリー</b>: 429 Too Many Requestsで明確なエラーメッセージを返却</li>
 * </ul>
 *
 * <h3>条件付き有効化:</h3>
 * <p>rate-limit.enabled=trueの場合のみこのフィルターが有効になります。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private final ProxyManager<byte[]> proxyManager;

    @Value("${rate-limit.enabled}")
    private boolean rateLimitEnabled;

    @Value("${rate-limit.auth.rpm}")
    private int authRateLimitRpm;

    @Value("${rate-limit.api.authenticated.rpm}")
    private int apiAuthenticatedRateLimitRpm;

    @Value("${rate-limit.api.anonymous.rpm}")
    private int apiAnonymousRateLimitRpm;

    /**
     * フィルター処理のメインロジック
     *
     * <p>リクエストごとにレート制限を確認し、制限を超えた場合は429エラーを返します。</p>
     *
     * @param request HTTPリクエスト
     * @param response HTTPレスポンス
     * @param filterChain フィルターチェーン
     * @throws ServletException サーブレット例外
     * @throws IOException I/O例外
     */
    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // レート制限が無効の場合はスキップ
        if (!rateLimitEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String key = getRateLimitKey(request, path);

        // レート制限対象外のパスの場合はスキップ
        if (key == null) {
            filterChain.doFilter(request, response);
            return;
        }

        BucketConfiguration config = getBucketConfiguration(key);

        // Bucketを取得（Redis上で分散管理）
        Bucket bucket = proxyManager.builder()
            .build(key.getBytes(StandardCharsets.UTF_8), () -> config);

        // トークンを消費（1リクエスト = 1トークン）
        if (bucket.tryConsume(1)) {
            // レート制限内 → 次のフィルターへ
            filterChain.doFilter(request, response);
        } else {
            // レート制限超過 → 例外をスロー（FilterChainExceptionHandlerで処理）
            log.warn("レート制限超過: path={}, key={}", path, key);
            throw new RateLimitExceededException("リクエスト数が制限を超えました。しばらく待ってから再試行してください。");
        }
    }

    /**
     * レート制限キーを生成
     *
     * <p>エンドポイントの種類に応じて、適切な識別方法でキーを生成します。</p>
     *
     * <h3>キー生成ルール:</h3>
     * <ul>
     *   <li><b>認証エンドポイント</b>: IPアドレスベース（ブルートフォース対策）</li>
     *   <li><b>APIプロキシ（認証済み）</b>: セッションIDベース（認証済みユーザー単位）</li>
     *   <li><b>APIプロキシ（未認証）</b>: IPアドレスベース（DoS攻撃対策）
     *       <ul>
     *         <li>未認証でもアクセス可能なエンドポイント（書籍検索等）を保護</li>
     *         <li>認証済みユーザーより厳しい制限を適用</li>
     *       </ul>
     *   </li>
     *   <li><b>その他</b>: nullを返し、レート制限なし</li>
     * </ul>
     *
     * @param request HTTPリクエスト
     * @param path リクエストパス
     * @return レート制限キー（nullの場合は制限なし）
     */
    private String getRateLimitKey(HttpServletRequest request, String path) {
        // ログアウトは除外
        if (path.equals("/bff/auth/logout")) {
            return null;
        }

        // 認証エンドポイント: IPアドレスベースのレート制限（ブルートフォース対策）
        // ForwardedHeaderFilterにより、request.getRemoteAddr()が正しいクライアントIPを返す
        if (path.startsWith("/bff/auth")) {
            return "rate_limit:auth:" + request.getRemoteAddr();
        }

        // APIプロキシ: 認証済み/未認証で異なるレート制限
        if (path.startsWith("/api")) {
            HttpSession session = request.getSession(false);
            if (session == null) {
                // 未認証ユーザー: IPアドレスベースのレート制限
                // 未認証でもアクセス可能なエンドポイントを保護
                return "rate_limit:api:anonymous:" + request.getRemoteAddr();
            }
            // 認証済みユーザー: セッションIDベースのレート制限
            return "rate_limit:api:authenticated:" + session.getId();
        }

        // その他のパスはレート制限なし
        return null;
    }

    /**
     * レート制限キーに応じたBucket設定を取得
     *
     * <p>エンドポイントごとに異なるレート制限ルールを適用します。</p>
     *
     * <h3>設定内容:</h3>
     * <ul>
     *   <li><b>認証エンドポイント</b>: 環境変数で設定可能（デフォルト: 30リクエスト/分）</li>
     *   <li><b>APIプロキシ（認証済み）</b>: 環境変数で設定可能（デフォルト: 200リクエスト/分）</li>
     *   <li><b>APIプロキシ（未認証）</b>: 環境変数で設定可能（デフォルト: 100リクエスト/分）</li>
     * </ul>
     *
     * @param key レート制限キー
     * @return Bucket設定
     */
    private BucketConfiguration getBucketConfiguration(String key) {
        long limit;

        if (key.startsWith("rate_limit:auth:")) {
            // 認証エンドポイント
            limit = authRateLimitRpm;
        } else if (key.startsWith("rate_limit:api:authenticated:")) {
            // APIプロキシ（認証済みユーザー）
            limit = apiAuthenticatedRateLimitRpm;
        } else if (key.startsWith("rate_limit:api:anonymous:")) {
            // APIプロキシ（未認証ユーザー）
            limit = apiAnonymousRateLimitRpm;
        } else {
            // デフォルト（想定外のケース）
            limit = 100;
        }

        return BucketConfiguration.builder()
            .addLimit(
                Bandwidth.builder()
                    .capacity(limit)
                    .refillIntervally(limit, Duration.ofMinutes(1))
                    .build()
            )
            .build();
    }

    /**
     * フィルターを適用すべきでないリクエストを判定
     *
     * <p>以下のエンドポイントはレート制限の対象外とします：</p>
     * <ul>
     *   <li>/actuator/health - 監視システムからの頻繁なヘルスチェック</li>
     *   <li>/bff/login/oauth2/code/** - IdPからのコールバック（1回のみ）</li>
     *   <li>/oauth2/authorization/** - OAuth2認証開始（リダイレクト）</li>
     * </ul>
     *
     * @param request HTTPリクエスト
     * @return フィルターをスキップする場合はtrue
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();

        // ヘルスチェックはレート制限対象外
        // OAuth2コールバックはレート制限対象外（IdPからのリクエスト）
        // OAuth2認証開始はレート制限対象外
        if (path.equals("/actuator/health")
            || path.startsWith("/bff/login/oauth2/code/")
            || path.startsWith("/oauth2/authorization/")) {
            return true;
        }

        return false;
    }
}
