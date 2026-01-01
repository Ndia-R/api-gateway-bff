package com.example.api_gateway_bff.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.api_gateway_bff.dto.LogoutResponse;
import com.example.api_gateway_bff.service.AuthService;

import org.springframework.http.ResponseEntity;
import lombok.extern.slf4j.Slf4j;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/bff/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    @Value("${app.frontend.default-url}")
    private String defaultFrontendUrl;

    @Value("${CORS_ALLOWED_ORIGINS:}")
    private String corsAllowedOrigins;

    /**
     * Referer/OriginヘッダーからフロントエンドのベースURLを抽出
     *
     * <p>マルチアプリケーション対応のため、以下の優先順位でフロントエンドURLを決定します：</p>
     * <ol>
     *   <li><b>セッションに保存された値</b>（OAuth2コールバック対応）</li>
     *   <li><b>Refererヘッダー</b>からフルパス抽出（同一VPS + Nginx対応）</li>
     *   <li><b>Originヘッダー</b>（異なるVPS対応）</li>
     *   <li><b>デフォルトフロントエンドURL</b>（フォールバック）</li>
     * </ol>
     *
     * <h3>動作例:</h3>
     * <pre>
     * Referer: https://app.example.com/my-books/reviews
     *   → https://app.example.com/my-books
     *
     * Referer: https://app.example.com/my-music/playlists
     *   → https://app.example.com/my-music
     *
     * Origin: https://books.example.com
     *   → https://books.example.com
     * </pre>
     *
     * @param request HTTPリクエスト
     * @param session HTTPセッション
     * @return フロントエンドのベースURL
     */
    private String getFrontendUrlFromRequest(HttpServletRequest request, HttpSession session) {
        // 1. セッションに保存された値を優先（OAuth2コールバック対応）
        String savedFrontendUrl = (String) session.getAttribute("original_frontend_url");
        if (savedFrontendUrl != null) {
            log.debug("Using saved frontend URL from session: {}", savedFrontendUrl);
            return savedFrontendUrl;
        }

        // 2. Refererからフルパスを抽出（同一VPS + Nginx対応）
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            try {
                URI refererUri = new URI(referer);
                String baseOrigin = refererUri.getScheme() + "://" + refererUri.getAuthority();

                if (isOriginAllowed(baseOrigin)) {
                    String appBasePath = extractAppBasePath(refererUri.getPath());
                    String fullUrl = appBasePath != null ? baseOrigin + appBasePath : baseOrigin;

                    // セッションに保存（OAuth2フロー用）
                    session.setAttribute("original_frontend_url", fullUrl);
                    log.debug("Extracted frontend URL from Referer: {}", fullUrl);
                    return fullUrl;
                }
            } catch (URISyntaxException e) {
                log.warn("Invalid Referer: {}", referer);
            }
        }

        // 3. Originのみ（異なるVPS対応）
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && isOriginAllowed(origin)) {
            session.setAttribute("original_frontend_url", origin);
            log.debug("Using Origin header: {}", origin);
            return origin;
        }

        // 4. デフォルト値
        log.debug("Using default frontend URL: {}", defaultFrontendUrl);
        return defaultFrontendUrl;
    }

    /**
     * パスから最初のセグメント（アプリのベースパス）を抽出
     *
     * <h3>抽出例:</h3>
     * <pre>
     * /my-books/reviews → /my-books
     * /my-music/playlists → /my-music
     * /login → null (ルート直下)
     * </pre>
     *
     * @param path リクエストパス
     * @return アプリのベースパス（存在しない場合はnull）
     */
    private String extractAppBasePath(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return null;
        }

        // 最初のスラッシュを除去してセグメントを取得
        String[] segments = path.substring(1).split("/");

        if (segments.length > 0 && !segments[0].isEmpty()) {
            // 特定のパス（BFF自身のエンドポイント）は除外
            String firstSegment = segments[0];
            if (firstSegment.equals("bff") || firstSegment.equals("api") ||
                firstSegment.equals("auth-callback")) {
                return null;
            }

            return "/" + firstSegment;
        }

        return null;
    }

    /**
     * Originが許可リストに含まれているか検証
     *
     * <p>CORS_ALLOWED_ORIGINSで指定されたオリジンのみを許可します。</p>
     * <p>ワイルドカード（*）に対応しています。</p>
     *
     * @param origin 検証対象のオリジン
     * @return 許可されている場合はtrue
     */
    private boolean isOriginAllowed(String origin) {
        if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
            log.warn("CORS_ALLOWED_ORIGINS not configured, rejecting origin: {}", origin);
            return false;
        }

        String[] allowedOrigins = corsAllowedOrigins.split(",");
        for (String allowed : allowedOrigins) {
            allowed = allowed.trim();
            // ワイルドカード対応（例: https://localhost:*）
            if (origin.matches(allowed.replace("*", ".*").replace(".", "\\."))) {
                return true;
            }
        }

        log.warn("Origin not allowed: {}", origin);
        return false;
    }

    /**
     * ログインエンドポイント
     *
     * <p>このエンドポイントは認証が必要なため、Spring Securityによって保護されています。</p>
     * <ul>
     *   <li><b>未認証ユーザー</b>がアクセスすると、コントローラーに到達する前にSpring SecurityがOAuth2認証フロー（IdPログイン画面）へリダイレクトします。</li>
     *   <li><b>認証済みユーザー</b>がアクセスすると、このメソッドが実行され、フロントエンドの認証後コールバックページへリダイレクトされます。</li>
     * </ul>
     *
     * <p><b>return_toパラメータ（認証後のリダイレクト先機能）:</b></p>
     * <ul>
     *   <li>フロントエンドから<code>return_to</code>パラメータで復帰先URL（例: /my-reviews）を受け取ります</li>
     *   <li>受け取った<code>return_to</code>をセッションに保存し、認証完了後にフロントエンドに渡します</li>
     *   <li>認証済みユーザーの場合は、即座に<code>/auth-callback?return_to=XXX</code>にリダイレクトします</li>
     *   <li>未認証ユーザーの場合は、OAuth2認証フロー後に<code>authenticationSuccessHandler</code>がセッションから<code>return_to</code>を取得してリダイレクトします</li>
     * </ul>
     *
     * @param returnTo 認証後の復帰先URL（例: /my-reviews）。省略可能。
     * @param request HTTPリクエスト（Referer/Origin取得に使用）
     * @param session HTTPセッション（returnToの保存に使用）
     * @param response HTTPレスポンス（リダイレクトに使用）
     */
    @GetMapping("/login")
    public void login(
        @RequestParam(name = "return_to", required = false) String returnTo,
        HttpServletRequest request,
        HttpSession session,
        HttpServletResponse response
    ) throws IOException {
        log.debug("Authenticated user accessing /bff/auth/login");

        // Referer/Originヘッダーから動的にフロントエンドURLを取得（マルチアプリ対応）
        String dynamicFrontendUrl = getFrontendUrlFromRequest(request, session);

        // フロントエンドから受け取った復帰先URLをセッションに保存
        // OAuth2認証フロー後、authenticationSuccessHandlerがこの値を使用する
        if (returnTo != null && !returnTo.isBlank()) {
            session.setAttribute("redirect_after_login", returnTo);
        }

        // 認証済みのため、フロントエンドの認証後コールバックページにリダイレクト
        // returnToがある場合はクエリパラメータとして付与
        String redirectUrl = dynamicFrontendUrl + "/auth-callback";
        if (returnTo != null && !returnTo.isBlank()) {
            redirectUrl += "?return_to=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
        }

        log.info("Redirecting to: {}", redirectUrl);
        response.sendRedirect(redirectUrl);
    }

    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(
        HttpServletRequest request,
        HttpServletResponse response,
        @AuthenticationPrincipal OAuth2User principal,
        @RequestParam(value = "complete", defaultValue = "false") boolean complete
    ) {
        LogoutResponse logoutResponse = authService.logout(request, response, principal, complete);
        return ResponseEntity.ok(logoutResponse);
    }
}