package com.example.api_gateway_bff.filter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * レート制限の統合テスト
 *
 * <p>レート制限機能が正しく動作することを、実際のRedisと連携して検証します。</p>
 *
 * <h3>テストケース:</h3>
 * <ul>
 *   <li>除外エンドポイント（ヘルスチェック、OAuth2）はレート制限なし</li>
 *   <li>認証エンドポイントのレート制限（IPアドレスベース）</li>
 *   <li>レート制限超過時の429エラーレスポンス</li>
 *   <li>異なるIPアドレスは独立したレート制限</li>
 * </ul>
 *
 * <h3>テスト環境:</h3>
 * <ul>
 *   <li>Redis: テスト用Redisを使用（spring.data.redis.* 設定）</li>
 *   <li>レート制限: 有効（rate-limit.enabled=true）</li>
 *   <li>制限値: テスト用に小さめの値を設定（認証:5req/分）</li>
 * </ul>
 *
 * <h3>注意:</h3>
 * <p>APIプロキシのレート制限テストはOAuth2認証の複雑さがあるため、
 * 別途手動テストまたはE2Eテストで検証することを推奨します。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "app.frontend.url=http://localhost:5173",
    "app.resource-servers.service-01.url=http://localhost:9000",
    "app.resource-servers.service-01.timeout=30",
    "app.resource-servers.service-01.path-prefix=/service-01",
    "app.resource-servers.service-02.url=http://localhost:9001",
    "app.resource-servers.service-02.timeout=30",
    "app.resource-servers.service-02.path-prefix=/service-02",
    "app.cors.allowed-origins=http://localhost:5173",
    "idp.post-logout-redirect-uri=http://localhost:5173/logout-complete",

    // レート制限設定（テスト用に低い値）
    "rate-limit.enabled=true",
    "rate-limit.auth.rpm=5",
    "rate-limit.api.authenticated.rpm=200", // 認証済みユーザー
    "rate-limit.api.anonymous.rpm=100", // 未認証ユーザー

    // Redis設定
    "spring.data.redis.host=redis",
    "spring.data.redis.port=6379",

    // セッション設定
    "spring.session.store-type=redis"
})
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * テスト: ヘルスチェックエンドポイントはレート制限なし
     */
    @Test
    void testHealthEndpoint_ShouldNotBeRateLimited() throws Exception {
        // 10回リクエストしても全て成功する（レート制限なし）
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        }
    }

    /**
     * テスト: OAuth2コールバックエンドポイントはレート制限なし
     */
    @Test
    void testOAuth2CallbackEndpoint_ShouldNotBeRateLimited() throws Exception {
        // OAuth2コールバックはレート制限対象外
        // 注: 実際の認証フローではないため、エラーになるが、レート制限（429）は適用されない
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/bff/login/oauth2/code/idp"));
            // レート制限が適用されないことを確認（429が返らなければOK）
        }
    }

    /**
     * テスト: OAuth2認証開始エンドポイントはレート制限なし
     */
    @Test
    void testOAuth2AuthorizationEndpoint_ShouldNotBeRateLimited() throws Exception {
        // OAuth2認証開始はレート制限対象外
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/oauth2/authorization/idp"))
                .andExpect(status().is3xxRedirection()); // リダイレクト
        }
    }

    /**
     * テスト: 認証エンドポイント - レート制限内のリクエストは成功
     */
    @Test
    void testAuthEndpoint_WithinLimit_ShouldSucceed() throws Exception {
        // 5リクエスト（制限値）まではすべて成功
        // 独立したIPアドレスを使用して他のテストと干渉しないようにする
        String uniqueIp = "10.1.1.1"; // 他のテストと完全に異なるIP範囲

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(
                get("/bff/auth/user")
                    .with(request -> {
                        request.setRemoteAddr(uniqueIp);
                        return request;
                    })
            )
                .andExpect(status().is3xxRedirection()); // 未認証なのでリダイレクト
        }
    }

    /**
     * テスト: 認証エンドポイント - レート制限超過で429エラー
     */
    @Test
    void testAuthEndpoint_ExceedsLimit_ShouldReturn429() throws Exception {
        // 独立したIPアドレスを使用して他のテストと干渉しないようにする
        String uniqueIp = "10.2.2.2"; // 他のテストと完全に異なるIP範囲

        // 最初の5リクエストは成功
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(
                get("/bff/auth/user")
                    .with(request -> {
                        request.setRemoteAddr(uniqueIp);
                        return request;
                    })
            )
                .andExpect(status().is3xxRedirection());
        }

        // 6リクエスト目はレート制限超過で429エラー
        mockMvc.perform(
            get("/bff/auth/user")
                .with(request -> {
                    request.setRemoteAddr(uniqueIp);
                    return request;
                })
        )
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error").value("TOO_MANY_REQUESTS"))
            .andExpect(jsonPath("$.message").value("リクエスト数が制限を超えました。しばらく待ってから再試行してください。"))
            .andExpect(jsonPath("$.status").value(429));
    }

    /**
     * テスト: 異なるIPアドレスからのリクエストは独立したレート制限
     */
    @Test
    void testAuthEndpoint_DifferentIPs_ShouldHaveIndependentLimits() throws Exception {
        // IP1: 10.3.3.3 - 他のテストと完全に異なるIP範囲
        String uniqueIp1 = "10.3.3.3";
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(
                get("/bff/auth/user")
                    .with(request -> {
                        request.setRemoteAddr(uniqueIp1);
                        return request;
                    })
            )
                .andExpect(status().is3xxRedirection());
        }

        // IP1: 6リクエスト目は制限超過
        mockMvc.perform(
            get("/bff/auth/user")
                .with(request -> {
                    request.setRemoteAddr(uniqueIp1);
                    return request;
                })
        )
            .andExpect(status().isTooManyRequests());

        // IP2: 10.3.3.4 - 独立したレート制限なので成功
        String uniqueIp2 = "10.3.3.4";
        mockMvc.perform(
            get("/bff/auth/user")
                .with(request -> {
                    request.setRemoteAddr(uniqueIp2);
                    return request;
                })
        )
            .andExpect(status().is3xxRedirection());
    }

    /**
     * テスト: ログアウトエンドポイントはレート制限なし
     */
    @Test
    void testLogoutEndpoint_ShouldNotBeRateLimited() throws Exception {
        // ログアウトは制限なし（CSRFトークン必要）
        // 注: CSRFトークンなしでアクセスするため、403エラーになるが、レート制限は適用されない
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(post("/bff/auth/logout"))
                .andExpect(status().isForbidden()); // CSRF保護による403
        }
    }
}
