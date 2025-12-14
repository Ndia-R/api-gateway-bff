package com.example.api_gateway_bff.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;

import com.example.api_gateway_bff.config.ResourceServerProperties;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * APIプロキシコントローラー
 *
 * <p>フロントエンドからのすべてのAPIリクエストを複数のリソースサーバーに振り分けて転送する。</p>
 *
 * <h3>BFFパターンの実装（集約型）</h3>
 * <ul>
 *   <li>トークンをフロントエンドから隠蔽し、BFF側で管理</li>
 *   <li>認証済みユーザーのアクセストークンを自動的に付与</li>
 *   <li>未認証ユーザーのリクエストはトークンなしでリソースサーバーへ転送</li>
 *   <li>リソースサーバーのレスポンスを透過的に転送</li>
 *   <li>パスベースのルーティングで複数のバックエンドサービスに対応</li>
 * </ul>
 *
 * <h3>ルーティング例</h3>
 * <ul>
 *   <li>/api/my-books/list → my-books サービス → http://my-books-api:8080/list</li>
 *   <li>/api/my-musics/search → my-musics サービス → http://my-musics-api:8081/search</li>
 * </ul>
 *
 * <h3>権限制御</h3>
 * <p>権限制御はリソースサーバー側で行う。BFFは認証・未認証に関わらず
 * すべてのリクエストを転送し、リソースサーバーが適切に権限チェックを実施する。
 * 認証が必要なエンドポイントにアクセスした場合、リソースサーバーが401を返す。</p>
 *
 * <h3>WebClient利用</h3>
 * <p>WebClientはWebClientConfigで定義されたサービスごとのBeanを使用。
 * コネクションプールの再利用によりパフォーマンスとリソース効率を向上。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ApiProxyController {

    private final Map<String, WebClient> webClients;
    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final ResourceServerProperties resourceServerProperties;

    /**
     * 除外すべきレスポンスヘッダー
     *
     * <p>これらのヘッダーはSpring Bootが自動的に設定するため、
     * リソースサーバーからのレスポンスヘッダーをそのままコピーすると
     * 重複や競合が発生する可能性があります。</p>
     *
     * <ul>
     *   <li><b>transfer-encoding</b>: Spring Bootが自動的にチャンク転送を設定</li>
     *   <li><b>connection</b>: HTTP/1.1コネクション管理（Keep-Alive等）</li>
     *   <li><b>keep-alive</b>: コネクション維持設定</li>
     *   <li><b>upgrade</b>: プロトコルアップグレード（WebSocket等）</li>
     *   <li><b>server</b>: サーバー情報の漏洩防止</li>
     *   <li><b>content-length</b>: Spring Bootが自動的に計算</li>
     * </ul>
     */
    private static final Set<String> EXCLUDED_RESPONSE_HEADERS = Set.of(
        "transfer-encoding",
        "connection",
        "keep-alive",
        "upgrade",
        "server",
        "content-length"
    );

    /**
     * リクエストパスからリソースサーバーを選択
     *
     * <p>パスプレフィックスに基づいて、適切なリソースサーバーを選択します。</p>
     *
     * <h3>ルーティング例:</h3>
     * <ul>
     *   <li>/my-books/list → my-books サービス</li>
     *   <li>/my-musics/search → my-musics サービス</li>
     * </ul>
     *
     * @param path リクエストパス（/api を除いた部分）
     * @return サービス名
     * @throws IllegalArgumentException パスに対応するサービスが見つからない場合
     */
    private String selectService(String path) {
        return resourceServerProperties.getResourceServers()
            .entrySet()
            .stream()
            .filter(entry -> path.startsWith(entry.getValue().getPathPrefix()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No service found for path: " + path));
    }

    /**
     * すべてのAPIリクエストを適切なリソースサーバーにプロキシ
     *
     * <p>このエンドポイントは /api/** 配下のすべてのリクエストを受け付け、
     * パスベースのルーティングで適切なリソースサーバーに転送する。
     * 認証済みの場合のみアクセストークンを付与する。</p>
     * 
     * <h3>処理フロー</h3>
     * <ol>
     *   <li>リクエストパスからHTTPメソッドとパスを取得</li>
     *   <li>パスプレフィックスに基づいて転送先サービスを選択</li>
     *   <li>パスプレフィックスを削除してターゲットパスを作成</li>
     *   <li>UriBuilderで安全なURIを構築（クエリパラメータ自動エンコード）</li>
     *   <li>認証状態を確認し、認証済みの場合のみアクセストークンを設定</li>
     *   <li>Content-Typeヘッダーを設定</li>
     *   <li>選択したリソースサーバーにリクエストを転送（サービスごとのタイムアウト）</li>
     *   <li>レスポンスのステータスコード・ヘッダー・ボディを保持して返却</li>
     * </ol>
     *
     * <h3>認証・権限制御</h3>
     * <ul>
     *   <li>認証チェック: BFFでは行わない（リソースサーバーに委譲）</li>
     *   <li>権限チェック: リソースサーバー側で実施</li>
     *   <li>未認証の場合: トークンなしでリソースサーバーへ転送</li>
     *   <li>認証済みの場合: アクセストークンを付与してリソースサーバーへ転送</li>
     * </ul>
     *
     * @param request HTTPリクエスト
     * @param body リクエストボディ（GET/DELETEの場合はnull）
     * @return リソースサーバーからのレスポンス（ステータスコード・ヘッダー・ボディ）
     * @throws IllegalArgumentException パスに対応するサービスが見つからない場合
     */
    @RequestMapping("/**")
    @SuppressWarnings("null") // HttpServletRequest/UriBuilder/WebClient APIの型アノテーション互換性のため警告を抑制
    public ResponseEntity<String> proxyAll(
        HttpServletRequest request,
        @RequestBody(required = false) String body
    ) {
        // リクエスト情報を取得
        String method = request.getMethod();
        String path = request.getRequestURI().replace("/api", "");

        // パスからサービスを選択
        String serviceName = selectService(path);
        WebClient selectedClient = webClients.get(serviceName);
        ResourceServerProperties.ServerConfig serviceConfig = resourceServerProperties.getResourceServers()
            .get(serviceName);

        // パスプレフィックスを削除してターゲットパスを作成
        String pathPrefix = serviceConfig.getPathPrefix();
        String targetPath = path.startsWith(pathPrefix) ? path.substring(pathPrefix.length()) : path;

        log.debug("Routing request: path={}, service={}, targetPath={}", path, serviceName, targetPath);

        // WebClientリクエストビルダー
        WebClient.RequestBodyUriSpec requestBuilder = selectedClient.method(HttpMethod.valueOf(method));

        // URI設定（共通）
        WebClient.RequestBodySpec bodySpec = requestBuilder.uri(uriBuilder -> {
            URI baseUri = URI.create(serviceConfig.getUrl());
            UriBuilder builder = uriBuilder
                .scheme(baseUri.getScheme())
                .host(baseUri.getHost())
                .port(baseUri.getPort())
                .path(targetPath);

            // クエリパラメータを追加（自動エンコード）
            request.getParameterMap()
                .forEach((key, values) -> builder.queryParam(key, (Object[]) values));

            return builder.build();
        });

        // ボディ設定 + ヘッダー設定
        WebClient.RequestHeadersSpec<?> headersSpec;

        if ("GET".equals(method) || "DELETE".equals(method)) {
            // GET/DELETE: ボディなし
            headersSpec = bodySpec;
        } else {
            // POST/PUT/PATCH: ボディ設定
            if (body != null && !body.isEmpty()) {
                headersSpec = bodySpec.bodyValue(body);
            } else {
                headersSpec = bodySpec;
            }
        }

        // 認証状態を確認
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        final boolean isAuthenticated = authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);

        // ヘッダー設定（共通）
        headersSpec = headersSpec.headers(h -> {
            // 認証済みの場合のみアクセストークンを付与
            if (isAuthenticated && authentication != null) {
                OAuth2AuthorizedClient client = authorizedClientRepository.loadAuthorizedClient(
                    "idp",
                    authentication,
                    request
                );

                if (client != null && client.getAccessToken() != null) {
                    h.setBearerAuth(client.getAccessToken().getTokenValue());
                } else {
                    log.warn("Authenticated user {} has no access token", authentication.getName());
                }
            }

            // リクエストのContent-Typeをリソースサーバーに転送
            String contentType = request.getContentType();
            if (contentType != null) {
                h.setContentType(MediaType.parseMediaType(contentType));
            }
        });

        try {
            // リクエスト実行（ステータスコード・ヘッダー・ボディをすべて保持）
            ResponseEntity<String> response = headersSpec
                .exchangeToMono(clientResponse -> {
                    // ステータスコードを保持
                    HttpStatusCode statusCode = clientResponse.statusCode();

                    // レスポンスヘッダーをフィルタリング
                    HttpHeaders originalHeaders = clientResponse.headers().asHttpHeaders();
                    HttpHeaders filteredHeaders = new HttpHeaders();
                    originalHeaders.forEach((name, values) -> {
                        if (!EXCLUDED_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                            filteredHeaders.addAll(name, values);
                        }
                    });

                    // ボディを取得してレスポンスを構築
                    // 空のレスポンス（204 No Content等）の場合はdefaultIfEmptyで空文字列を設定
                    return clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("") // 空の場合のデフォルト値
                        .map(
                            responseBody -> ResponseEntity
                                .status(statusCode)
                                .headers(filteredHeaders)
                                .body(responseBody)
                        );
                })
                .block();

            if (response == null) {
                log.error("WebClient returned null response - possible timeout or connection error");
                throw new RuntimeException("リソースサーバーへのリクエストが失敗しました");
            }

            return response;

        } catch (Exception e) {
            log.error("Error during API proxy request", e);
            throw e;
        }
    }
}
