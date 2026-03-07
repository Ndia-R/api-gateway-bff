package com.example.api_gateway_bff.config;

import com.example.api_gateway_bff.util.FrontendUrlUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * カスタムOAuth2認可リクエストリゾルバー
 *
 * <p>このクラスは、OAuth2認証フロー開始時に以下の処理を行います：</p>
 * <ul>
 *   <li>PKCE (Proof Key for Code Exchange) の適用</li>
 *   <li><code>return_to</code>パラメータと元のフロントエンドURLをセッションに保存</li>
 *   <li>サインアップリクエストをIdPの登録エンドポイントにルーティング</li>
 * </ul>
 *
 * <h3>処理フロー:</h3>
 * <pre>
 * 1. 未認証ユーザーが /bff/auth/login または /bff/auth/signup にアクセス
 * 2. Spring Securityが認証を要求し、このリゾルバーを呼び出し
 * 3. セッションに保存された `SavedRequest` から本来のリクエストURIを取得
 * 4. URIに応じて、ログインまたはサインアップの処理を分岐
 *   - サインアップの場合: IdPの登録エンドポイントURIを構築
 *   - ログインの場合: IdPの認証エンドポイントURIをそのまま利用
 * 5. PKCEパラメータを付与したOAuth2認可リクエストを生成
 * 6. 最終的なURIにリダイレクト
 * </pre>
 *
 * <h3>実装方式:</h3>
 * <p>{@code DefaultOAuth2AuthorizationRequestResolver}がfinalクラスのため、
 * 継承ではなく<b>委譲パターン</b>を使用しています。</p>
 *
 * <h3>セキュリティ考慮事項:</h3>
 * <ul>
 *   <li>return_toの検証は {@code authenticationSuccessHandler} で実施</li>
 *   <li>セッションはSpring Session + Redisで管理</li>
 * </ul>
 */
@Slf4j
public class CustomAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver defaultResolver;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    @Value("${app.oauth2.registration-path:}")
    private String registrationPath;

    @Value("${app.frontend.default-url}")
    private String defaultFrontendUrl;

    @Value("${CORS_ALLOWED_ORIGINS:}")
    private String corsAllowedOrigins;

    /**
     * コンストラクタ
     *
     * @param clientRegistrationRepository OAuth2クライアント登録情報
     * @param authorizationRequestBaseUri OAuth2認証開始パス（デフォルト: /oauth2/authorization）
     */
    public CustomAuthorizationRequestResolver(
        ClientRegistrationRepository clientRegistrationRepository,
        String authorizationRequestBaseUri
    ) {
        this.defaultResolver = new DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository,
            authorizationRequestBaseUri
        );
        this.defaultResolver.setAuthorizationRequestCustomizer(
            OAuth2AuthorizationRequestCustomizers.withPkce()
        );
    }

    /**
     * OAuth2認可リクエストを解決（カスタマイズ）
     *
     * <p>このメソッドは、Spring SecurityがOAuth2認証フローを開始する際に呼び出されます。</p>
     * <p>本来のリクエストURIをセッションから復元し、サインアップリクエストの場合は
     * IdPの登録エンドポイントにリダイレクトするようリクエストをカスタマイズします。</p>
     *
     * @param request HTTPリクエスト
     * @return OAuth2認可リクエスト（PKCEパラメータ含む）
     */
    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        OAuth2AuthorizationRequest authorizationRequest = this.defaultResolver.resolve(request);
        if (authorizationRequest == null) {
            return null;
        }

        // 認証済みユーザーのパスワード変更フロー:
        // AuthControllerが session["pending_kc_action"] を設定した後、/oauth2/authorization/idp にリダイレクトし、
        // Spring SecurityのOAuth2AuthorizationRequestRedirectFilterがこのresolve(request)を呼ぶ。
        // AuthControllerがすでに original_frontend_url / redirect_after_login を保存済みのため
        // saveReturnToParameter() による上書きを避け、kc_action のみを付加する。
        HttpSession session = request.getSession(false);
        if (session != null) {
            String kcAction = (String) session.getAttribute("pending_kc_action");
            if (kcAction != null) {
                session.removeAttribute("pending_kc_action");
                log.info("pending_kc_action detected: adding kc_action={} to authorization request.", kcAction);
                return OAuth2AuthorizationRequest.from(authorizationRequest)
                    .additionalParameters(params -> params.put("kc_action", kcAction))
                    .build();
            }
        }

        // 元のリクエストURIを取得（未認証ユーザーがBFFのprotectedエンドポイントにアクセスした場合）
        SavedRequest savedRequest = this.requestCache.getRequest(request, null);
        String originalRequestUri = null;
        if (savedRequest != null) {
            try {
                originalRequestUri = new URI(savedRequest.getRedirectUrl()).getPath();
            } catch (URISyntaxException e) {
                log.warn("Could not parse URI from SavedRequest", e);
            }
        }

        log.info("=== OAuth2 Authorization Flow Started ===");
        log.info("Request URI (internal): {}", request.getRequestURI());
        log.info("Original Request URI: {}", originalRequestUri);

        saveReturnToParameter(request);

        // サインアップリクエストの場合
        if ("/bff/auth/signup".equals(originalRequestUri)) {
            // registration-pathが設定されていなければ、通常のログインフローにフォールバック
            if (registrationPath == null || registrationPath.isBlank()) {
                log.warn(
                    "Sign-up is not supported: app.oauth2.registration-path is not configured. Falling back to login."
                );
                return authorizationRequest;
            }

            log.info("Sign-up request detected. Building URI for registration.");
            String registrationId = "idp";
            ClientRegistration clientRegistration = clientRegistrationRepository.findByRegistrationId(registrationId);
            String issuerUri = clientRegistration.getProviderDetails().getIssuerUri();

            // issuer-uriと登録パスを結合して、最終的な登録URIを構築
            String registrationUriString = issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1)
                : issuerUri;
            registrationUriString += registrationPath;

            log.info("Redirecting to registration URI: {}", registrationUriString);

            // パラメータは維持しつつ、URIだけを差し替えた新しいリクエストを構築
            return OAuth2AuthorizationRequest.from(authorizationRequest)
                .authorizationUri(registrationUriString)
                .build();
        }

        return authorizationRequest;
    }

    /**
     * OAuth2認可リクエストを解決（登録ID指定版）
     *
     * <p>このオーバーロードメソッドは、特定のクライアント登録ID（例: "idp"）を指定してOAuth2認証フローを開始する際に呼び出されます。</p>
     *
     * @param request HTTPリクエスト
     * @param clientRegistrationId クライアント登録ID（例: "idp"）
     * @return OAuth2認可リクエスト（PKCEパラメータ含む）
     */
    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest authorizationRequest = this.defaultResolver.resolve(request, clientRegistrationId);
        if (authorizationRequest == null) {
            return null;
        }
        // このフローでも `return_to` を保存する
        saveReturnToParameter(request);
        return authorizationRequest;
    }

    /**
     * return_toパラメータと元のフロントエンドURLをセッションに保存（マルチアプリ対応）
     *
     * <p>このメソッドは、リクエストから以下を取得してセッションに保存します：</p>
     * <ul>
     *   <li><code>return_to</code>パラメータ → <code>redirect_after_login</code></li>
     *   <li>Referer/OriginヘッダーからフロントエンドURL → <code>original_frontend_url</code></li>
     * </ul>
     *
     * @param request HTTPリクエスト
     */
    private void saveReturnToParameter(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String returnTo = request.getParameter("return_to");
        if (returnTo != null && !returnTo.isBlank()) {
            session.setAttribute("redirect_after_login", returnTo);
            log.info(
                "Saved 'redirect_after_login' to session: {} (Session ID: {})",
                sanitizeForLog(returnTo),
                session.getId()
            );
        }
        String frontendUrl = getFrontendUrlFromRequest(request);
        session.setAttribute("original_frontend_url", frontendUrl);
        log.info(
            "Saved 'original_frontend_url' to session: {} (Session ID: {})",
            sanitizeForLog(frontendUrl),
            session.getId()
        );
    }

    /**
     * Referer/OriginヘッダーからフロントエンドのベースURLを抽出
     *
     * <p>マルチアプリケーション対応のため、以下の優先順位でフロントエンドURLを決定します：</p>
     * <ol>
     *   <li><b>Refererヘッダー</b>からフルパス抽出（同一VPS + Nginx対応）</li>
     *   <li><b>Originヘッダー</b>（異なるVPS対応）</li>
     *   <li><b>デフォルトフロントエンドURL</b>（フォールバック）</li>
     * </ol>
     *
     * @param request HTTPリクエスト
     * @return フロントエンドのベースURL
     */
    private static String sanitizeForLog(String input) {
        if (input == null)
            return null;
        return input.replaceAll("[\r\n\t]", "_");
    }

    private String getFrontendUrlFromRequest(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        String frontendUrl = FrontendUrlUtils.extractFrontendUrlFromReferer(referer, corsAllowedOrigins);
        if (frontendUrl != null) {
            return frontendUrl;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && FrontendUrlUtils.isOriginAllowed(origin, corsAllowedOrigins)) {
            log.debug("Using Origin header: {}", origin);
            return origin;
        }
        log.debug("Using default frontend URL: {}", this.defaultFrontendUrl);
        return this.defaultFrontendUrl;
    }
}
