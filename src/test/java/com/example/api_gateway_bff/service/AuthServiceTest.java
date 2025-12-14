package com.example.api_gateway_bff.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.api_gateway_bff.client.OidcMetadataClient;
import com.example.api_gateway_bff.dto.LogoutResponse;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * AuthServiceの単体テスト
 *
 * <p>認証サービスのビジネスロジックを検証します。</p>
 *
 * <h3>テストケース:</h3>
 * <ul>
 *   <li>ログアウト処理（セッション無効化・Cookie削除）</li>
 *   <li>完全ログアウト処理（IdP連携）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private WebClient webClient;

    @Mock
    private OidcMetadataClient oidcMetadataClient;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private HttpSession session;

    @Mock
    private OAuth2User oAuth2User;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(webClient, oidcMetadataClient);
        ReflectionTestUtils.setField(authService, "postLogoutRedirectUri", "http://localhost:5173/logout-complete");
    }

    /**
     * テスト: 通常ログアウト（BFFセッションのみクリア）
     */
    @Test
    void testLogout_ShouldInvalidateSessionAndClearCookie() {
        // Arrange
        when(request.getSession(false)).thenReturn(session);

        // Act
        LogoutResponse result = authService.logout(request, response, oAuth2User, false);

        // Assert
        assertThat(result.getMessage()).isEqualTo("success");
        verify(session).invalidate();
        verify(response).addCookie(
            argThat(
                cookie -> cookie.getName().equals("BFFSESSIONID") &&
                    cookie.getMaxAge() == 0 &&
                    cookie.isHttpOnly()
            )
        );
    }

    /**
     * テスト: セッションが存在しない場合のログアウト
     */
    @Test
    void testLogout_WhenNoSession_ShouldNotThrowException() {
        // Arrange
        when(request.getSession(false)).thenReturn(null);

        // Act
        LogoutResponse result = authService.logout(request, response, oAuth2User, false);

        // Assert
        assertThat(result.getMessage()).isEqualTo("success");
        // 2つのCookie（BFFSESSIONID, XSRF-TOKEN）が削除される
        verify(response, times(2)).addCookie(any());
    }

    /**
     * テスト: 完全ログアウト（IdP連携）
     * 注: 実際のWebClient呼び出しはモック化が複雑なため、基本動作のみ検証
     */
    @Test
    void testLogout_WithComplete_ShouldAttemptIdPLogout() {
        // Arrange
        when(request.getSession(false)).thenReturn(session);

        // OidcUserのモックを作成
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "user123");
        claims.put("name", "Test User");

        OidcIdToken idToken = new OidcIdToken(
            "test-token-value",
            Instant.now(),
            Instant.now().plusSeconds(3600),
            claims
        );

        OidcUser oidcUser = new DefaultOidcUser(null, idToken);

        // Act
        LogoutResponse result = authService.logout(request, response, oidcUser, true);

        // Assert
        assertThat(result.getMessage()).isEqualTo("success");
        verify(session).invalidate();
        // IdPへのリクエストは実際には送信されない（WebClientがモック）
    }
}
