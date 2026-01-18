package com.example.api_gateway_bff.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import com.example.api_gateway_bff.client.OidcMetadataClient;
import com.example.api_gateway_bff.dto.LogoutResponse;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AuthService の単体テスト
 *
 * <p>ログアウト処理のビジネスロジックをテストします。</p>
 *
 * <h3>テスト内容:</h3>
 * <ul>
 *   <li>通常ログアウト（BFFセッションのみクリア）</li>
 *   <li>完全ログアウト（OIDCプロバイダーセッションも無効化）</li>
 *   <li>OIDCプロバイダー接続失敗時の動作</li>
 * </ul>
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private OidcMetadataClient oidcMetadataClient;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private HttpSession session;

    @InjectMocks
    private AuthService authService;

    @SuppressWarnings("null")
    @BeforeEach
    void setUp() {
        // SecurityContextをクリア
        SecurityContextHolder.clearContext();

        // postLogoutRedirectUriをリフレクションで設定
        ReflectionTestUtils.setField(authService, "postLogoutRedirectUri", "http://localhost:5173/logout-complete");
    }

    // ═══════════════════════════════════════════════════════════════
    // 通常ログアウトのテスト
    // ═══════════════════════════════════════════════════════════════

    @Test
    void 通常ログアウトでBFFセッションがクリアされる() {
        // Arrange
        when(request.getSession(false)).thenReturn(session);

        // Act
        LogoutResponse result = authService.logout(request, response, null, false);

        // Assert
        assertEquals("success", result.getMessage());
        assertNull(result.getWarning());

        // セッション無効化を確認
        verify(session).invalidate();

        // Cookieクリアを確認（BFFSESSIONID + XSRF-TOKEN）
        verify(response, times(2)).addCookie(any(Cookie.class));
    }

    // ═══════════════════════════════════════════════════════════════
    // 完全ログアウトのテスト（成功ケース）
    // ═══════════════════════════════════════════════════════════════

    @SuppressWarnings("null")
    @Test
    void 完全ログアウトでOIDCプロバイダーにログアウトリクエストが送信される() {
        // Arrange
        when(request.getSession(false)).thenReturn(session);

        // OidcUserのモック作成
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-id-token")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .subject("test-user")
            .claim("sub", "test-user")
            .build();

        OidcUser oidcUser = new DefaultOidcUser(null, idToken);

        // OidcMetadataClientのモック
        when(oidcMetadataClient.getEndSessionEndpoint())
            .thenReturn("http://keycloak:8080/realms/test/protocol/openid-connect/logout");

        // WebClientのモック
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.exchangeToMono(any())).thenReturn(Mono.just("success"));

        // Act
        LogoutResponse result = authService.logout(request, response, oidcUser, true);

        // Assert
        assertEquals("success", result.getMessage());
        assertNull(result.getWarning());

        // WebClientが呼ばれたことを確認
        verify(webClient).get();
    }

    // ═══════════════════════════════════════════════════════════════
    // 完全ログアウトのテスト（失敗ケース）
    // ═══════════════════════════════════════════════════════════════

    @SuppressWarnings("null")
    @Test
    void OIDCプロバイダー接続失敗時もBFFログアウトは成功する() {
        // Arrange
        when(request.getSession(false)).thenReturn(session);

        // OidcUserのモック作成
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-id-token")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .subject("test-user")
            .claim("sub", "test-user")
            .build();

        OidcUser oidcUser = new DefaultOidcUser(null, idToken);

        // OidcMetadataClientのモック
        when(oidcMetadataClient.getEndSessionEndpoint())
            .thenReturn("http://keycloak:8080/realms/test/protocol/openid-connect/logout");

        // WebClientのモック（接続失敗をシミュレート）
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.exchangeToMono(any()))
            .thenReturn(Mono.error(new RuntimeException("Connection failed")));

        // Act
        LogoutResponse result = authService.logout(request, response, oidcUser, true);

        // Assert
        assertEquals("success", result.getMessage());
        assertNotNull(result.getWarning());
        assertTrue(result.getWarning().contains("認証サーバーのログアウトに失敗"));

        // セッションは無効化される
        verify(session).invalidate();
    }

    @Test
    void IDトークンがない場合OIDCログアウトはスキップされる() {
        // Arrange
        when(request.getSession(false)).thenReturn(session);

        // Act
        LogoutResponse result = authService.logout(request, response, null, true);

        // Assert
        assertEquals("success", result.getMessage());
        assertNull(result.getWarning());

        // WebClientは呼ばれない
        verify(webClient, never()).get();
    }

    @Test
    void エンドセッションエンドポイントがない場合は警告を返す() {
        // Arrange
        when(request.getSession(false)).thenReturn(session);

        // OidcUserのモック作成
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-id-token")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .subject("test-user")
            .claim("sub", "test-user")
            .build();

        OidcUser oidcUser = new DefaultOidcUser(null, idToken);

        // OidcMetadataClientのモック（エンドポイントなし）
        when(oidcMetadataClient.getEndSessionEndpoint()).thenReturn(null);

        // Act
        LogoutResponse result = authService.logout(request, response, oidcUser, true);

        // Assert
        assertEquals("success", result.getMessage());
        assertNotNull(result.getWarning());
        assertTrue(result.getWarning().contains("認証サーバーのログアウトに失敗"));
    }
}
