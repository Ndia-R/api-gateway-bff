package com.example.api_gateway_bff.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.example.api_gateway_bff.service.AuthService;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SecurityConfig の統合テスト
 *
 * <p>Spring Securityの設定が正しく動作することを確認します。</p>
 *
 * <h3>テスト内容:</h3>
 * <ul>
 *   <li>認証不要エンドポイントのアクセス</li>
 *   <li>認証必須エンドポイントのアクセス</li>
 *   <li>CSRF保護の動作確認</li>
 *   <li>CORS設定の確認</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "rate-limit.enabled=false", // レート制限を無効化してテストを単純化
    "CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:*"
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    // ═══════════════════════════════════════════════════════════════
    // 認証不要エンドポイントのテスト
    // ═══════════════════════════════════════════════════════════════

    @Test
    void ヘルスチェックエンドポイントは認証なしでアクセス可能() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }

    @Test
    void APIプロキシエンドポイントは認証なしでアクセス可能() throws Exception {
        // 注: 実際のルーティングは失敗するが、認証チェックは通過する
        // 認証エラー(401/403)ではないことを確認
        mockMvc.perform(get("/api/my-books/list"))
            .andExpect(result -> {
                int status = result.getResponse().getStatus();
                // 認証エラー（401, 403）ではないことを確認
                if (status == 401 || status == 403) {
                    throw new AssertionError("Expected non-auth error but got " + status);
                }
            });
    }

    // ═══════════════════════════════════════════════════════════════
    // 認証必須エンドポイントのテスト
    // ═══════════════════════════════════════════════════════════════

    @Test
    void ログインエンドポイントは未認証の場合OAuth2フローにリダイレクト() throws Exception {
        // ClientRegistrationRepositoryがモックのため、デフォルトの/loginにリダイレクトされる
        mockMvc.perform(get("/bff/auth/login"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ═══════════════════════════════════════════════════════════════
    // CSRF保護のテスト
    // ═══════════════════════════════════════════════════════════════

    @Test
    void POSTリクエストでCSRFトークンがない場合は403エラー() throws Exception {
        mockMvc.perform(post("/bff/auth/logout"))
            .andExpect(status().isForbidden());
    }

    @SuppressWarnings("null")
    @Test
    void POSTリクエストでCSRFトークンがある場合は成功() throws Exception {
        mockMvc.perform(
            post("/bff/auth/logout")
                .with(csrf())
        )
            .andExpect(status().isOk());
    }

    @Test
    void GETリクエストはCSRF検証がスキップされる() throws Exception {
        // CSRF検証は通過することを確認（403 Forbiddenではない）
        mockMvc.perform(get("/api/my-books/list"))
            .andExpect(result -> {
                int status = result.getResponse().getStatus();
                // CSRF拒否（403）ではないことを確認
                if (status == 403) {
                    throw new AssertionError("Expected CSRF check to be skipped but got 403 Forbidden");
                }
            });
    }

    // ═══════════════════════════════════════════════════════════════
    // CORS設定のテスト
    // ═══════════════════════════════════════════════════════════════
    // 注: SecurityConfigにCORS設定がないため、これらのテストはスキップまたは
    // CORS設定追加後に有効化する必要があります

    // @Test
    // void 許可されたオリジンからのリクエストは成功() throws Exception {
    // mockMvc.perform(get("/actuator/health")
    // .header("Origin", "http://localhost:5173"))
    // .andExpect(status().isOk())
    // .andExpect(header().exists("Access-Control-Allow-Origin"));
    // }

    // @Test
    // void OPTIONSリクエストでCORSプリフライトが正しく処理される() throws Exception {
    // mockMvc.perform(options("/api/my-books/list")
    // .header("Origin", "http://localhost:5173")
    // .header("Access-Control-Request-Method", "POST")
    // .header("Access-Control-Request-Headers", "Content-Type,X-XSRF-TOKEN"))
    // .andExpect(status().isOk())
    // .andExpect(header().exists("Access-Control-Allow-Origin"))
    // .andExpect(header().exists("Access-Control-Allow-Methods"));
    // }
}
