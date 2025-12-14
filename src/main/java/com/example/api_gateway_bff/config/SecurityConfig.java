package com.example.api_gateway_bff.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import com.example.api_gateway_bff.filter.FilterChainExceptionHandler;
import com.example.api_gateway_bff.filter.RateLimitFilter;

import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Spring Security設定クラス
 *
 * <p>このBFF (Backend for Frontend) アプリケーションのセキュリティ設定を管理します。</p>
 *
 * <h3>主な責務:</h3>
 * <ul>
 *   <li>OAuth2 + PKCE認証フローの設定</li>
 *   <li>CSRF保護（CookieベースのCSRFトークン）</li>
 *   <li>CORS設定（フロントエンドからのクロスオリジンリクエスト許可）</li>
 *   <li>認可ルール（どのエンドポイントが認証不要/認証必須か）</li>
 *   <li>OAuth2クライアント情報の保存先設定（Redisセッション連携）</li>
 * </ul>
 *
 * <h3>セキュリティ設計:</h3>
 * <ul>
 *   <li><b>BFFパターン</b>: トークンはBFF側で完全管理、フロントエンドはセッションCookieのみ使用</li>
 *   <li><b>PKCE対応</b>: Authorization Code Flowの認可コード盗聴攻撃を防止</li>
 *   <li><b>CSRF保護</b>: 状態変更操作（POST/PUT/DELETE）を保護</li>
 *   <li><b>HttpOnly Cookie</b>: セッションCookieへのJavaScriptアクセスを防止（XSS対策）</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private FilterChainExceptionHandler filterChainExceptionHandler;

    @Autowired(required = false)
    private RateLimitFilter rateLimitFilter;

    /**
     * フロントエンドアプリケーションのURL
     * OAuth2認証成功後のリダイレクト先として使用
     */
    @Value("${app.frontend.url}")
    private String frontendUrl;

    /**
     * Spring Securityのフィルターチェーン設定
     *
     * <p>このメソッドはSpring Securityの中核となる設定で、以下の順序で処理されます：</p>
     * <ol>
     *   <li><b>CORS処理</b>: クロスオリジンリクエストの検証</li>
     *   <li><b>CSRF処理</b>: CSRFトークンの検証とCookie設定</li>
     *   <li><b>認証チェック</b>: セッションCookieからユーザー情報を取得</li>
     *   <li><b>認可チェック</b>: エンドポイントへのアクセス権限確認</li>
     *   <li><b>OAuth2処理</b>: 未認証の場合、IdPへリダイレクト</li>
     * </ol>
     *
     * @param http Spring SecurityのHttpSecurity設定オブジェクト
     * @param pkceResolver PKCE対応のOAuth2認可リクエストリゾルバ
     * @return 構築されたSecurityFilterChain
     * @throws Exception 設定エラー時
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, OAuth2AuthorizationRequestResolver pkceResolver)
        throws Exception {

        // ═══════════════════════════════════════════════════════════════
        // フィルターチェーン例外ハンドラー: 最初に追加
        // ═══════════════════════════════════════════════════════════════
        // すべてのフィルターで発生した例外をキャッチし、統一されたエラーレスポンスを返す
        // GlobalExceptionHandlerと同じErrorResponse形式を使用
        http.addFilterBefore(filterChainExceptionHandler, SecurityContextHolderFilter.class);

        // ═══════════════════════════════════════════════════════════════
        // レート制限フィルター: FilterChainExceptionHandlerの後に追加
        // ═══════════════════════════════════════════════════════════════
        // SecurityContextHolderFilterの前に配置し、認証処理前にレート制限を実施
        // これにより、過剰なリクエストを早期にブロックしてリソースを保護
        // rate-limit.enabled=trueの場合のみ追加
        if (rateLimitFilter != null) {
            http.addFilterBefore(rateLimitFilter, SecurityContextHolderFilter.class);
        }

        http

            // ═══════════════════════════════════════════════════════════════
            // CSRF保護設定: POST/PUT/DELETE等の状態変更操作を保護
            // ═══════════════════════════════════════════════════════════════
            .csrf(
                csrf -> csrf
                    // CSRFトークンをCookieに保存（HttpOnly=false: JavaScriptから読み取り可能）
                    // フロントエンドは X-XSRF-TOKEN ヘッダーにトークンを設定して送信
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    // CSRFトークンをリクエスト属性として利用可能にする
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )
            // CSRFトークンを確実にCookieに設定するカスタムフィルターを追加
            // Spring SecurityのCsrfFilterの直後に実行され、XSRF-TOKEN Cookieを生成
            .addFilterAfter(
                new CsrfCookieFilter(),
                CsrfFilter.class
            )
            // ═══════════════════════════════════════════════════════════════
            // 認可設定: エンドポイントごとのアクセス制御
            // ═══════════════════════════════════════════════════════════════
            .authorizeHttpRequests(
                authz -> authz
                    // ────────────────────────────────────────────────────
                    // 認証不要エンドポイント（permitAll）
                    // ────────────────────────────────────────────────────
                    .requestMatchers(
                        // ヘルスチェック: Kubernetes等の監視システムからアクセス
                        "/actuator/health",

                        // ログアウト: セッション無効化後も200 OKを返すため認証不要
                        // ※実際の処理はAuthControllerで認証状態を確認
                        "/bff/auth/logout",

                        // OAuth2認証開始: Spring Security標準のエンドポイント
                        "/oauth2/**",

                        // OAuth2コールバック: IdPからの認可コード受け取り
                        "/bff/login/oauth2/**",

                        // OpenID Connect Discovery: OAuth2プロバイダーのメタデータ
                        "/.well-known/**",

                        // APIプロキシ: 認証・権限チェックはリソースサーバー側で実施
                        // BFFは認証状態に関わらずすべてのリクエストをプロキシする
                        // 未認証の場合はトークンなしでリソースサーバーへ転送
                        "/api/**"
                    )
                    .permitAll()

                    // ────────────────────────────────────────────────────
                    // 上記以外のすべてのリクエストは認証必須
                    // ────────────────────────────────────────────────────
                    // /bff/auth/user, /bff/auth/login など
                    .anyRequest()
                    .authenticated()
            )
            // ═══════════════════════════════════════════════════════════════
            // OAuth2ログイン設定: IdPとの認証フロー
            // ═══════════════════════════════════════════════════════════════
            .oauth2Login(
                oauth2 -> oauth2
                    // 認可エンドポイント設定: PKCE対応のリゾルバを使用
                    // code_challenge/code_verifierを自動生成
                    .authorizationEndpoint(authz -> authz.authorizationRequestResolver(pkceResolver))

                    // リダイレクションエンドポイント: IdPからのコールバックを受け取るパス
                    .redirectionEndpoint(redirection -> redirection.baseUri("/bff/login/oauth2/code/*"))

                    // 認証成功ハンドラー: OAuth2認証完了後にフロントエンドへリダイレクト
                    .successHandler(authenticationSuccessHandler())
            )
            // ═══════════════════════════════════════════════════════════════
            // ログアウト設定: カスタムエンドポイントで処理
            // ═══════════════════════════════════════════════════════════════
            // Spring Security標準のログアウト機能（/logout）は使用しない
            // 理由: 通常ログアウト/完全ログアウトの2種類を実装するため
            // 実際の処理は AuthController.logout() で実装
            .logout(
                logout -> logout.disable()
            );

        return http.build();
    }

    /**
     * OAuth2認証成功後のカスタムハンドラー
     *
     * <p>IdPでの認証完了後、このハンドラーがフロントエンドにリダイレクトします。</p>
     *
     * <p><b>オープンリダイレクト脆弱性対策:</b>
     * <code>continue</code> パラメータで指定されたリダイレクト先が、
     * 安全なURL（同一ホストまたは許可されたホスト）であるかを検証します。</p>
     *
     * @return OAuth2認証成功ハンドラー
     */
    @Bean
    public AuthenticationSuccessHandler authenticationSuccessHandler() {
        return (request, response, authentication) -> {
            // デフォルトのリダイレクト先: フロントエンドの認証コールバックページ
            String redirectUrl = frontendUrl + "/auth-callback";

            // continueパラメータがある場合はそれを検証して優先
            String continueParam = request.getParameter("continue");
            if (continueParam != null && !continueParam.isBlank() && isUrlSafe(continueParam)) {
                redirectUrl = continueParam;
            }

            // フロントエンドにリダイレクト
            response.sendRedirect(redirectUrl);
        };
    }

    /**
     * URLが安全なリダイレクト先であるかを検証する
     *
     * @param url 検証するURL文字列
     * @return 安全な場合はtrue、そうでない場合はfalse
     */
    private boolean isUrlSafe(String url) {
        try {
            // フロントエンドのホストを取得
            String frontendHost = new URI(frontendUrl).getHost();
            if (frontendHost == null) {
                // 設定が不正な場合は安全側に倒す
                return false;
            }

            // 許可するホストのリスト
            final Set<String> allowedHosts = Set.of("localhost", frontendHost);

            URI redirectUri = new URI(url);

            // 1. ホストが指定されていない相対パス（例: /dashboard）は安全とみなす
            if (redirectUri.getHost() == null) {
                return true;
            }

            // 2. ホストが許可リストに含まれているかチェック
            return allowedHosts.contains(redirectUri.getHost());

        } catch (URISyntaxException e) {
            // 不正な形式のURLは危険とみなし、リダイレクトを許可しない
            return false;
        }
    }

    /**
     * OAuth2認可クライアント情報の保存先設定
     *
     * <p><b>注意:</b> Spring Boot 3.xでは、OAuth2AuthorizedClientRepositoryは自動設定されます。
     * Spring Session + Redisを使用している場合、自動的にセッションに保存されます。</p>
     *
     * <p>このBean定義は削除されました。Spring Bootの自動設定に任せています。</p>
     *
     * <h3>自動設定の内容:</h3>
     * <ul>
     *   <li><b>保存先</b>: Spring Session (Redis)</li>
     *   <li><b>保存される情報</b>: アクセストークン、リフレッシュトークン、IDトークン</li>
     *   <li><b>タイムアウト</b>: セッションタイムアウト（30分）</li>
     * </ul>
     *
     * <p>削除理由: Spring Bootが自動的に適切な実装を提供するため、明示的なBean定義は不要。</p>
     */
    // @Bean
    // public OAuth2AuthorizedClientRepository authorizedClientRepository(...) {
    // 削除済み - Spring Bootの自動設定を使用
    // }

    /**
     * PKCE対応のOAuth2認可リクエストリゾルバ
     *
     * <p>PKCE (Proof Key for Code Exchange) は、Authorization Code Flowの
     * セキュリティを強化するための仕組みです。</p>
     *
     * <h3>PKCEの仕組み:</h3>
     * <ol>
     *   <li><b>code_verifier生成</b>: ランダムな文字列を生成（43-128文字）</li>
     *   <li><b>code_challenge生成</b>: <code>BASE64URL(SHA256(code_verifier))</code></li>
     *   <li><b>認可リクエスト</b>: code_challengeをIdPに送信</li>
     *   <li><b>トークン交換</b>: code_verifierをIdPに送信して検証</li>
     * </ol>
     *
     * <h3>防止できる攻撃:</h3>
     * <ul>
     *   <li><b>認可コード盗聴攻撃</b>: 認可コードを盗んでも、code_verifierがないとトークン取得不可</li>
     *   <li><b>リダイレクトURI改ざん</b>: 正規のアプリケーションのみがトークン取得可能</li>
     * </ul>
     *
     * <h3>生成されるパラメータ:</h3>
     * <pre>
     * 認可リクエスト:
     *   code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM
     *   code_challenge_method=S256
     *
     * トークン交換リクエスト:
     *   code_verifier=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
     * </pre>
     *
     * @param clientRegistrationRepository OAuth2クライアント登録情報
     * @return PKCE対応のOAuth2認可リクエストリゾルバ
     */
    @Bean
    public OAuth2AuthorizationRequestResolver pkceResolver(ClientRegistrationRepository clientRegistrationRepository) {
        // デフォルトのリゾルバを作成
        // 第2引数: OAuth2認証を開始するパス（/oauth2/authorization/{registrationId}）
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository,
            "/oauth2/authorization"
        );

        // PKCEカスタマイザーを適用
        // code_challengeとcode_verifierを自動生成し、認可リクエストに追加
        resolver.setAuthorizationRequestCustomizer(
            OAuth2AuthorizationRequestCustomizers.withPkce()
        );

        return resolver;
    }

}