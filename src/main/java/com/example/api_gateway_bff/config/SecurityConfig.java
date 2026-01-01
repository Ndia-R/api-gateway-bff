package com.example.api_gateway_bff.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import com.example.api_gateway_bff.filter.FilterChainExceptionHandler;
import com.example.api_gateway_bff.filter.RateLimitFilter;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpSession;

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
@Slf4j
public class SecurityConfig {

    @Autowired
    private FilterChainExceptionHandler filterChainExceptionHandler;

    @Autowired(required = false)
    private RateLimitFilter rateLimitFilter;

    /**
     * デフォルトフロントエンドURL（フォールバック用）
     * OAuth2認証成功後のリダイレクト先として使用
     */
    @Value("${app.frontend.default-url}")
    private String defaultFrontendUrl;

    /**
     * CORS許可オリジンリスト
     * マルチアプリケーション対応のセキュリティ検証に使用
     */
    @Value("${CORS_ALLOWED_ORIGINS:}")
    private String corsAllowedOrigins;

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
     * @param clientRegistrationRepository OAuth2クライアント登録情報
     * @return 構築されたSecurityFilterChain
     * @throws Exception 設定エラー時
     */
    @Bean
    public SecurityFilterChain filterChain(
        HttpSecurity http,
        ClientRegistrationRepository clientRegistrationRepository
    ) throws Exception {

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
                    // 認可エンドポイント設定: カスタムリゾルバーを使用（PKCE + return_to保存）
                    // code_challenge/code_verifierを自動生成し、return_toをセッションに保存
                    .authorizationEndpoint(
                        authz -> authz.authorizationRequestResolver(
                            customAuthorizationRequestResolver(clientRegistrationRepository)
                        )
                    )

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
     * <p>IdPでの認証完成後、このハンドラーがフロントエンドにリダイレクトします。</p>
     *
     * <p><b>マルチアプリケーション対応:</b></p>
     * <ul>
     *   <li>セッションに保存された<code>original_frontend_url</code>から動的にリダイレクト先を決定</li>
     *   <li>同一VPS + Nginx構成: <code>https://app.example.com/my-books</code> → <code>/my-books/auth-callback</code></li>
     *   <li>異なるVPS構成: <code>https://books.example.com</code> → <code>/auth-callback</code></li>
     * </ul>
     *
     * <p><b>認証後のリダイレクト先機能（return_toパラメータ）:</b></p>
     * <ul>
     *   <li>セッションに保存された<code>redirect_after_login</code>（復帰先URL）を取得</li>
     *   <li><code>/auth-callback?return_to=XXX</code>形式でフロントエンドにリダイレクト</li>
     *   <li>セキュリティ検証: <code>return_to</code>が安全なURL（相対パスまたは許可されたホスト）であるかチェック</li>
     *   <li>使用後はセッションから削除</li>
     * </ul>
     *
     * <p><b>オープンリダイレクト脆弱性対策:</b>
     * <code>return_to</code>パラメータで指定されたリダイレクト先が、
     * 安全なURL（同一ホストまたは許可されたホスト）であるかを検証します。</p>
     *
     * @return OAuth2認証成功ハンドラー
     */
    @Bean
    public AuthenticationSuccessHandler authenticationSuccessHandler() {
        return (request, response, authentication) -> {
            log.info("=== OAuth2 Authentication Success ===");

            HttpSession session = request.getSession(false);
            if (session == null) {
                log.error("Session is null after authentication!");
                response.sendRedirect(defaultFrontendUrl + "/auth-callback");
                return;
            }

            // セッションから保存されたフロントエンドURLを取得（マルチアプリ対応）
            String savedFrontendUrl = (String) session.getAttribute("original_frontend_url");
            String dynamicFrontendUrl = savedFrontendUrl != null ? savedFrontendUrl : defaultFrontendUrl;

            // 使用後はセッションから削除
            if (savedFrontendUrl != null) {
                session.removeAttribute("original_frontend_url");
                log.info("Using saved frontend URL: {}", savedFrontendUrl);
            } else {
                log.info("Using default frontend URL: {}", defaultFrontendUrl);
            }

            String returnTo = null;

            // 1. まず、明示的に保存されたredirect_after_loginを確認
            returnTo = (String) session.getAttribute("redirect_after_login");
            if (returnTo != null) {
                session.removeAttribute("redirect_after_login");
                log.info("Found explicit redirect_after_login: {}", returnTo);
            }

            // 2. 明示的なreturn_toがない場合、Spring SecurityのSavedRequestから取得
            if (returnTo == null) {
                HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
                SavedRequest savedRequest = requestCache.getRequest(request, response);

                if (savedRequest != null) {
                    String savedUrl = savedRequest.getRedirectUrl();
                    log.info("Found SavedRequest URL: {}", savedUrl);

                    // SavedRequestから元のリクエストのクエリパラメータを抽出
                    // 注意: URI.getQuery()はURLデコードを行うため、エンコードされた&(%26)が&に変換されてしまう
                    // そのため、URLから直接クエリ文字列を抽出する
                    int queryStart = savedUrl.indexOf('?');
                    if (queryStart != -1 && savedUrl.contains("return_to=")) {
                        String query = savedUrl.substring(queryStart + 1);

                        // クエリパラメータを & で分割
                        // return_to の値に含まれる %26 はエンコード済みなので & とは区別される
                        String[] queryParams = query.split("&");

                        for (String param : queryParams) {
                            if (param.startsWith("return_to=")) {
                                // return_to= の後ろの値を取得してデコード
                                String encodedValue = param.substring("return_to=".length());
                                returnTo = java.net.URLDecoder.decode(encodedValue, StandardCharsets.UTF_8);
                                log.info("Extracted return_to from SavedRequest: {}", returnTo);
                                break;
                            }
                        }
                    }
                }
            }

            // 動的に決定されたフロントエンドURLを使用（マルチアプリ対応）
            String redirectUrl = dynamicFrontendUrl + "/auth-callback";

            // returnToがある場合は、セキュリティ検証してクエリパラメータとして追加
            if (returnTo != null && !returnTo.isBlank()) {
                if (isUrlSafe(returnTo)) {
                    redirectUrl += "?return_to=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
                    log.info("Final return_to: {}", returnTo);
                } else {
                    log.warn("Unsafe redirect attempt blocked: {}", returnTo);
                }
            }

            log.info("Redirecting to: {}", redirectUrl);
            response.sendRedirect(redirectUrl);
        };
    }

    /**
     * URLが安全なリダイレクト先であるかを検証する（マルチアプリ対応）
     *
     * <p>CORS_ALLOWED_ORIGINSを使用してオープンリダイレクト脆弱性を防止します。</p>
     *
     * @param url 検証するURL文字列
     * @return 安全な場合はtrue、そうでない場合はfalse
     */
    private boolean isUrlSafe(String url) {
        try {
            URI redirectUri = new URI(url);

            // 1. ホストが指定されていない相対パス（例: /dashboard）は安全とみなす
            if (redirectUri.getHost() == null) {
                return true;
            }

            // 2. CORS_ALLOWED_ORIGINSで許可されたオリジンかチェック
            String origin = redirectUri.getScheme() + "://" + redirectUri.getAuthority();

            if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
                log.warn("CORS_ALLOWED_ORIGINS not configured, falling back to defaultFrontendUrl");
                // フォールバック: defaultFrontendUrlのホストと比較
                String frontendHost = new URI(defaultFrontendUrl).getHost();
                return frontendHost != null &&
                       (frontendHost.equals(redirectUri.getHost()) || "localhost".equals(redirectUri.getHost()));
            }

            // CORS_ALLOWED_ORIGINSに含まれているかチェック（ワイルドカード対応）
            String[] allowedOrigins = corsAllowedOrigins.split(",");
            for (String allowed : allowedOrigins) {
                allowed = allowed.trim();
                // ワイルドカード対応（例: https://localhost:*）
                if (origin.matches(allowed.replace("*", ".*").replace(".", "\\."))) {
                    return true;
                }
            }

            log.warn("Unsafe redirect attempt blocked: {}", url);
            return false;

        } catch (URISyntaxException e) {
            // 不正な形式のURLは危険とみなし、リダイレクトを許可しない
            log.warn("Invalid URL format: {}", url);
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
     * OAuth2認可クライアントマネージャー（トークン自動リフレッシュ対応）
     *
     * <p>このBeanは、OAuth2クライアントの認可状態を管理し、以下の機能を提供します：</p>
     * <ul>
     *   <li><b>トークン自動リフレッシュ</b>: アクセストークンの有効期限が切れた際、リフレッシュトークンを使用して自動更新</li>
     *   <li><b>認可コードフロー対応</b>: Authorization Code Flowによる初回トークン取得</li>
     *   <li><b>クライアントクレデンシャル対応</b>: サービス間通信用のトークン取得（必要に応じて）</li>
     * </ul>
     *
     * <h3>トークンリフレッシュの動作:</h3>
     * <ol>
     *   <li><b>トークン取得時</b>: {@code authorizedClientManager.authorize()}を呼び出し</li>
     *   <li><b>期限チェック</b>: アクセストークンの有効期限を自動的にチェック</li>
     *   <li><b>自動リフレッシュ</b>: 期限切れの場合、リフレッシュトークンを使用して新しいアクセストークンを取得</li>
     *   <li><b>Redis保存</b>: 新しいトークンをセッションに紐づけてRedisに保存</li>
     * </ol>
     *
     * <h3>セキュリティ上の利点:</h3>
     * <ul>
     *   <li>ユーザーに再ログインを強制せず、シームレスなセッション維持</li>
     *   <li>リフレッシュトークンは常にBFF（サーバー側）で管理され、フロントエンドに一切公開されない</li>
     *   <li>トークンリフレッシュは透過的に処理され、ユーザーエクスペリエンスに影響なし</li>
     * </ul>
     *
     * <h3>使用例:</h3>
     * <pre>
     * // ApiProxyControllerでの使用
     * OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
     *     .withClientRegistrationId("idp")
     *     .principal(authentication)
     *     .attribute(HttpServletRequest.class.getName(), request)
     *     .build();
     *
     * OAuth2AuthorizedClient authorizedClient =
     *     authorizedClientManager.authorize(authorizeRequest);
     *
     * // トークンが期限切れの場合、自動的にリフレッシュされる
     * String accessToken = authorizedClient.getAccessToken().getTokenValue();
     * </pre>
     *
     * @param clientRegistrationRepository OAuth2クライアント登録情報
     * @param authorizedClientRepository OAuth2認可クライアント保存先（Redisセッション連携）
     * @return OAuth2認可クライアントマネージャー
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
        ClientRegistrationRepository clientRegistrationRepository,
        OAuth2AuthorizedClientRepository authorizedClientRepository
    ) {
        // OAuth2認可プロバイダーの構築
        // - authorizationCode(): Authorization Code Flowによる初回トークン取得
        // - refreshToken(): リフレッシュトークンによる自動トークン更新
        // - clientCredentials(): クライアントクレデンシャルフロー（サービス間通信用、必要に応じて）
        OAuth2AuthorizedClientProvider authorizedClientProvider = OAuth2AuthorizedClientProviderBuilder.builder()
            .authorizationCode()
            .refreshToken()
            .clientCredentials()
            .build();

        // DefaultOAuth2AuthorizedClientManagerの構築
        // - clientRegistrationRepository: OAuth2クライアント情報（application.ymlから読み込み）
        // - authorizedClientRepository: トークン保存先（Spring Session + Redisで管理）
        DefaultOAuth2AuthorizedClientManager authorizedClientManager = new DefaultOAuth2AuthorizedClientManager(
            clientRegistrationRepository,
            authorizedClientRepository
        );

        // 認可プロバイダーを設定
        authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);

        return authorizedClientManager;
    }

    /**
     * カスタムOAuth2認可リクエストリゾルバー（PKCE + return_to保存）
     *
     * <p>このリゾルバーは以下の機能を提供します：</p>
     * <ul>
     *   <li><b>PKCE対応</b>: code_challenge/code_verifierを自動生成</li>
     *   <li><b>return_to保存</b>: 未認証時のリダイレクト先URLをセッションに保存</li>
     * </ul>
     *
     * <h3>PKCE (Proof Key for Code Exchange):</h3>
     * <p>Authorization Code Flowのセキュリティを強化する仕組み。</p>
     * <ol>
     *   <li><b>code_verifier生成</b>: ランダムな文字列を生成（43-128文字）</li>
     *   <li><b>code_challenge生成</b>: <code>BASE64URL(SHA256(code_verifier))</code></li>
     *   <li><b>認可リクエスト</b>: code_challengeをIdPに送信</li>
     *   <li><b>トークン交換</b>: code_verifierをIdPに送信して検証</li>
     * </ol>
     *
     * <h3>return_to保存機能:</h3>
     * <p>未認証ユーザーが<code>/bff/auth/login?return_to=/my-reviews</code>にアクセスした際、
     * OAuth2フロー開始前に<code>return_to</code>をセッションに保存し、認証完了後にフロントエンドに渡します。</p>
     *
     * <h3>動作例:</h3>
     * <pre>
     * 1. フロント → /bff/auth/login?return_to=/my-reviews
     * 2. Spring Security → このリゾルバーを呼び出し
     * 3. リゾルバー → return_toをセッションに保存
     * 4. OAuth2フロー開始 → Keycloak認証
     * 5. 認証成功 → authenticationSuccessHandler
     * 6. セッションからreturn_toを取得 → /auth-callback?return_to=/my-reviews
     * </pre>
     *
     * @param clientRegistrationRepository OAuth2クライアント登録情報
     * @return カスタムOAuth2認可リクエストリゾルバー
     */
    @Bean
    public OAuth2AuthorizationRequestResolver customAuthorizationRequestResolver(
        ClientRegistrationRepository clientRegistrationRepository
    ) {
        return new CustomAuthorizationRequestResolver(
            clientRegistrationRepository,
            "/oauth2/authorization"
        );
    }

}