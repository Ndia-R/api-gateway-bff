package com.example.api_gateway_bff.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

/**
 * RateLimitFilter の統合テスト
 *
 * <p>レート制限機能が正しく動作することを確認します。</p>
 *
 * <h3>テスト内容:</h3>
 * <ul>
 *   <li>認証エンドポイントのレート制限（30リクエスト/分）</li>
 *   <li>APIプロキシのレート制限（200リクエスト/分）</li>
 *   <li>除外エンドポイントの確認</li>
 *   <li>Redis連携の確認</li>
 * </ul>
 *
 * <p><b>注意:</b> このテストはRedisが起動している環境でのみ実行可能です。
 * Redisが起動していない場合は@Disabledでスキップされます。</p>
 */
@Disabled("Redis統合テストのため、Redisが起動している環境でのみ実行")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "rate-limit.enabled=true",
    "rate-limit.auth.rpm=5", // テスト用に少ない値に設定
    "rate-limit.api.authenticated.rpm=10",
    "rate-limit.api.anonymous.rpm=5"
})
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisTemplate<String, byte[]> redisTemplate;

    @BeforeEach
    void setUp() {
        // Redisのバケット情報をクリア（テスト間の独立性を確保）
        var connection = redisTemplate.getConnectionFactory();
        if (connection != null) {
            var redisConnection = connection.getConnection();
            redisConnection.serverCommands().flushAll();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 認証エンドポイントのレート制限テスト
    // ═══════════════════════════════════════════════════════════════

    @Test
    void 認証エンドポイントでレート制限を超えると429エラー() throws Exception {
        // 5リクエストまで成功
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/bff/auth/login"))
                .andExpect(status().is3xxRedirection()); // OAuth2リダイレクト
        }

        // 6リクエスト目は429エラー
        mockMvc.perform(get("/bff/auth/login"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error").value("TOO_MANY_REQUESTS"))
            .andExpect(jsonPath("$.message", containsString("リクエスト数が制限を超えました")));
    }

    // ═══════════════════════════════════════════════════════════════
    // APIプロキシのレート制限テスト（未認証）
    // ═══════════════════════════════════════════════════════════════

    @Test
    void APIプロキシで未認証ユーザーがレート制限を超えると429エラー() throws Exception {
        // 5リクエストまで成功（未認証の場合）
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/my-books/list"))
                .andExpect(status().is4xxClientError()); // ルーティングエラーまたは401
        }

        // 6リクエスト目は429エラー
        mockMvc.perform(get("/api/my-books/list"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error").value("TOO_MANY_REQUESTS"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 除外エンドポイントのテスト
    // ═══════════════════════════════════════════════════════════════

    @Test
    void ヘルスチェックエンドポイントはレート制限されない() throws Exception {
        // 10リクエスト送信しても成功
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        }
    }

    @Test
    void ログアウトエンドポイントはレート制限されない() throws Exception {
        // 10リクエスト送信しても成功
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/bff/auth/logout")
                    .with(csrf()))
                .andExpect(status().isOk());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Redis連携のテスト
    // ═══════════════════════════════════════════════════════════════

    @Test
    void レート制限状態がRedisに保存される() throws Exception {
        // 1リクエスト送信
        mockMvc.perform(get("/bff/auth/login"))
            .andExpect(status().is3xxRedirection());

        // Redisにバケット情報が保存されていることを確認
        // キーパターン: rate_limit:auth:{IPアドレス}
        Boolean hasKey = redisTemplate.hasKey("rate_limit:auth:127.0.0.1");
        assertTrue(hasKey != null && hasKey, "レート制限のバケット情報がRedisに保存されていません");
    }

    @Test
    void 異なるIPアドレスは独立したレート制限を受ける() throws Exception {
        // 同一IPで5リクエスト（制限到達）
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/bff/auth/login")
                    .with(request -> {
                        request.setRemoteAddr("192.168.1.100");
                        return request;
                    }))
                .andExpect(status().is3xxRedirection());
        }

        // 同一IPで6リクエスト目は429エラー
        mockMvc.perform(get("/bff/auth/login")
                .with(request -> {
                    request.setRemoteAddr("192.168.1.100");
                    return request;
                }))
            .andExpect(status().isTooManyRequests());

        // 異なるIPからのリクエストは成功
        mockMvc.perform(get("/bff/auth/login")
                .with(request -> {
                    request.setRemoteAddr("192.168.1.200");
                    return request;
                }))
            .andExpect(status().is3xxRedirection());
    }
}
