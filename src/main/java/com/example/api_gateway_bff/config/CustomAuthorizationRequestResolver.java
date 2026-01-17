package com.example.api_gateway_bff.config;

import com.example.api_gateway_bff.util.FrontendUrlUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * カスタムOAuth2認可リクエストリゾルバー
 *
 * <p>このクラスは、OAuth2認証フロー開始時に以下の処理を行います：</p>
 * <ul>
 *   <li>PKCE (Proof Key for Code Exchange) の適用</li>
 *   <li><code>return_to</code>パラメータをセッションに保存</li>
 * </ul>
 *
 * <h3>処理フロー:</h3>
 * <pre>
 * 1. 未認証ユーザーが /bff/auth/login?return_to=/my-reviews にアクセス
 * 2. Spring Securityがこのリゾルバーを呼び出し
 * 3. return_toパラメータを取得してセッションに保存
 * 4. PKCEを適用したOAuth2認可リクエストを生成
 * 5. IdPの認可エンドポイントにリダイレクト
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

    /**
     * デリゲート先のデフォルトリゾルバー
     * <p>PKCE対応とOAuth2標準処理を委譲</p>
     */
    private final DefaultOAuth2AuthorizationRequestResolver defaultResolver;

    /**
     * デフォルトフロントエンドURL（フォールバック用）
     */
    @Value("${app.frontend.default-url}")
    private String defaultFrontendUrl;

    /**
     * CORS許可オリジンリスト（マルチアプリケーション対応）
     */
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
        // デフォルトリゾルバーを作成
        this.defaultResolver = new DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository,
            authorizationRequestBaseUri
        );

        // PKCEカスタマイザーを適用
        // code_challengeとcode_verifierを自動生成
        this.defaultResolver.setAuthorizationRequestCustomizer(
            OAuth2AuthorizationRequestCustomizers.withPkce()
        );
    }

    /**
     * OAuth2認可リクエストを解決（カスタマイズ）
     *
     * <p>このメソッドは、Spring SecurityがOAuth2認証フローを開始する際に呼び出されます。</p>
     * <p>return_toパラメータをセッションに保存することで、認証完了後にフロントエンドに渡すことができます。</p>
     *
     * @param request HTTPリクエスト
     * @return OAuth2認可リクエスト（PKCEパラメータ含む）
     */
    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        // デフォルトのOAuth2認可リクエストを生成（PKCE適用済み）
        OAuth2AuthorizationRequest authorizationRequest = this.defaultResolver.resolve(request);

        // OAuth2認証フローが必要な場合のみログ出力
        if (authorizationRequest != null) {
            log.info("=== OAuth2 Authorization Flow Started ===");
            log.info("Request URI: {}", request.getRequestURI());
            log.info("Query String: {}", request.getQueryString());

            // return_toパラメータをセッションに保存
            saveReturnToParameter(request);

            log.info("Authorization URI: {}", authorizationRequest.getAuthorizationUri());
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
        log.info("=== OAuth2 Authorization Flow Started (Client: {}) ===", clientRegistrationId);
        log.info("Request URI: {}", request.getRequestURI());
        log.info("Query String: {}", request.getQueryString());

        // return_toパラメータをセッションに保存
        saveReturnToParameter(request);

        // デフォルトのOAuth2認可リクエストを生成（PKCE適用済み）
        return this.defaultResolver.resolve(request, clientRegistrationId);
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

        // 1. return_toパラメータを保存
        String returnTo = request.getParameter("return_to");
        if (returnTo != null && !returnTo.isBlank()) {
            session.setAttribute("redirect_after_login", returnTo);
            log.info("Saved 'redirect_after_login' to session: {} (Session ID: {})", returnTo, session.getId());
        } else {
            log.debug("No return_to parameter in request");
        }

        // 2. Referer/Originヘッダーからフロントエンドのベースパスを抽出してセッションに保存（マルチアプリ対応）
        String frontendUrl = getFrontendUrlFromRequest(request);
        session.setAttribute("original_frontend_url", frontendUrl);
        log.info("Saved 'original_frontend_url' to session: {} (Session ID: {})", frontendUrl, session.getId());
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
    private String getFrontendUrlFromRequest(HttpServletRequest request) {
        // 1. Refererからフルパスを抽出（同一VPS + Nginx対応）
        String referer = request.getHeader("Referer");
        String frontendUrl = FrontendUrlUtils.extractFrontendUrlFromReferer(referer, corsAllowedOrigins);
        if (frontendUrl != null) {
            return frontendUrl;
        }

        // 2. Originのみ（異なるVPS対応）
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && FrontendUrlUtils.isOriginAllowed(origin, corsAllowedOrigins)) {
            log.debug("Using Origin header: {}", origin);
            return origin;
        }

        // 3. デフォルト値
        log.debug("Using default frontend URL: {}", this.defaultFrontendUrl);
        return this.defaultFrontendUrl;
    }
}
