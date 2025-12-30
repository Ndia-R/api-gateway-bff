package com.example.api_gateway_bff.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.api_gateway_bff.client.OidcMetadataClient;
import com.example.api_gateway_bff.dto.LogoutResponse;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 認証サービス
 *
 * <p>BFFの認証関連ビジネスロジックを提供する。
 * ログアウト処理、ユーザー情報取得、OIDCプロバイダーとの連携を担当。
 *
 * <h3>WebClient利用</h3>
 * <p>WebClientはWebClientConfigで定義されたシングルトンBeanを使用。
 * OIDCプロバイダーへのログアウトリクエストでコネクションプールを再利用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final WebClient webClient;
    private final OidcMetadataClient oidcMetadataClient;

    @Value("${IDP_POST_LOGOUT_REDIRECT_URI}")
    private String postLogoutRedirectUri;

    // ===========================================
    // 公開メソッド（エントリーポイント）
    // ===========================================

    /**
     * ログアウト
     * @param request HTTPリクエスト
     * @param response HTTPレスポンス
     * @param principal 認証済みユーザー情報
     * @param complete 完全ログアウト（OIDCプロバイダーのセッションも無効化）するかどうか
     * @return ログアウト情報
     */
    public LogoutResponse logout(
        HttpServletRequest request,
        HttpServletResponse response,
        OAuth2User principal,
        boolean complete
    ) {
        // BFFセッション関連のクリア
        invalidateSession(request);
        clearSecurityContext();
        clearAuthenticationCookies(response);

        // OIDCプロバイダーのログアウト処理（完全ログアウト時のみ）
        boolean oidcLogoutSuccess = true;
        if (complete) {
            oidcLogoutSuccess = processOidcLogout(principal);
        }

        // OIDCプロバイダーのログアウトに失敗した場合、警告メッセージを含める
        if (complete && !oidcLogoutSuccess) {
            return new LogoutResponse(
                "success",
                "認証サーバーのログアウトに失敗しました。認証サーバー側のセッションが残っている可能性があります。"
            );
        }

        return new LogoutResponse("success");
    }

    // ===========================================
    // プライベートメソッド（処理順）
    // ===========================================

    /**
     * セッションを無効化する
     */
    private void invalidateSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /**
     * セキュリティコンテキストをクリアする
     */
    private void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 認証関連のCookieをすべて削除する
     *
     * <p>ログアウト時に以下のCookieを削除します:</p>
     * <ul>
     *   <li>BFFSESSIONID: BFFセッションCookie（HttpOnly=true）</li>
     *   <li>XSRF-TOKEN: CSRFトークンCookie（HttpOnly=false）</li>
     * </ul>
     *
     * <p><b>設計方針:</b></p>
     * <ul>
     *   <li>ログアウト後はクリーンな状態にするため、すべての認証関連Cookieを削除</li>
     *   <li>XSRF-TOKENもセッションに紐付いているため、ログアウト時に削除すべき</li>
     *   <li>次回ログイン時に新しいセッションと新しいCSRFトークンが生成される</li>
     * </ul>
     */
    private void clearAuthenticationCookies(HttpServletResponse response) {
        // BFFセッションCookieを削除
        clearCookie(response, "BFFSESSIONID", true);

        // CSRFトークンCookieを削除
        clearCookie(response, "XSRF-TOKEN", false);
    }

    /**
     * 指定されたCookieを削除する
     *
     * @param response HTTPレスポンス
     * @param name Cookie名
     * @param httpOnly HttpOnly属性（trueの場合、JavaScriptからアクセス不可）
     */
    private void clearCookie(HttpServletResponse response, String name, boolean httpOnly) {
        Cookie cookie = new Cookie(name, null);
        cookie.setPath("/");
        cookie.setHttpOnly(httpOnly);
        cookie.setMaxAge(0); // maxAge=0で削除
        response.addCookie(cookie);
    }

    /**
     * OIDCプロバイダーのログアウト処理を実行する（OpenID Connect RP-Initiated Logout方式）
     *
     * <p><b>重要な設計方針:</b></p>
     * <ul>
     *   <li>認証サーバーへの通知が失敗しても、BFFのログアウト処理は成功とみなす</li>
     *   <li>理由: ユーザーのBFFセッションは既に無効化済みであり、
     *       認証サーバー側のエラーでログアウトを妨げるべきではないため</li>
     *   <li>エラーは警告ログに記録され、呼び出し元に失敗を通知する</li>
     * </ul>
     *
     * @param principal 認証済みユーザー情報
     * @return OIDCプロバイダーのログアウトが成功した場合はtrue、失敗した場合はfalse
     */
    private boolean processOidcLogout(OAuth2User principal) {
        try {
            // OidcUserでない場合はスキップ
            if (!(principal instanceof OidcUser)) {
                log.debug("Principal is not an OidcUser, skipping OIDC provider logout");
                return true; // エラーではないのでtrueを返す
            }

            OidcUser oidcUser = (OidcUser) principal;
            String idToken = oidcUser.getIdToken().getTokenValue();
            if (idToken == null) {
                log.debug("No ID token found, skipping OIDC provider logout");
                return true; // エラーではないのでtrueを返す
            }

            // OIDC Discoveryから取得したend_session_endpointを使用してログアウトURLを構築
            String logoutUri = oidcMetadataClient.getEndSessionEndpoint();
            if (logoutUri == null || logoutUri.isBlank()) {
                log.warn("OIDC end_session_endpoint is not available. Skipping OIDC provider logout.");
                return false; // ログアウトエンドポイントがなければ失敗とみなす
            }

            String endSessionUrl = UriComponentsBuilder
                .fromUriString(logoutUri)
                .queryParam("id_token_hint", idToken)
                .queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
                .build()
                .toUriString();

            log.debug("Initiating OIDC provider logout: {}", endSessionUrl);

            // GETリクエストでOIDCプロバイダーのログアウトを実行（OpenID Connect仕様準拠）
            webClient.get()
                .uri(endSessionUrl)
                .exchangeToMono(clientResponse -> {
                    if (clientResponse.statusCode().is2xxSuccessful() ||
                        clientResponse.statusCode().is3xxRedirection()) {
                        return clientResponse.bodyToMono(String.class);
                    } else {
                        log.error("OIDC provider logout failed with status: {}", clientResponse.statusCode());
                        return clientResponse.bodyToMono(String.class)
                            .then(
                                Mono.error(
                                    new RuntimeException(
                                        "OIDC provider logout failed with " + clientResponse.statusCode()
                                    )
                                )
                            );
                    }
                })
                .block();

            return true; // 成功

        } catch (WebClientResponseException e) {
            log.warn("OIDC provider logout failed ({}), but BFF logout will continue", e.getStatusCode());
            return false; // 失敗

        } catch (WebClientException e) {
            log.warn("Could not connect to OIDC provider for logout, but BFF logout will continue");
            return false; // 失敗

        } catch (Exception e) {
            log.warn("OIDC provider logout error: {}, but BFF logout will continue", e.getMessage());
            return false; // 失敗
        }
    }

}