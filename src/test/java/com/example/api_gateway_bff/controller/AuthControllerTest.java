package com.example.api_gateway_bff.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.api_gateway_bff.dto.LogoutResponse;
import com.example.api_gateway_bff.service.AuthService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController の単体テスト
 *
 * <p>認証エンドポイントのリダイレクト処理をテストします。</p>
 *
 * <h3>テスト内容:</h3>
 * <ul>
 *   <li>ログインエンドポイント - 認証済みユーザー</li>
 *   <li>ログインエンドポイント - return_toパラメータ</li>
 *   <li>ログアウトエンドポイント - 通常ログアウト</li>
 *   <li>ログアウトエンドポイント - 完全ログアウト</li>
 * </ul>
 */
@WebMvcTest(AuthController.class)
@TestPropertySource(properties = {
    "app.frontend.url=http://localhost:5173",
    "CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:*",
    "rate-limit.enabled=false"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @BeforeEach
    void setUp() {
        // AuthServiceのモック設定
        when(authService.logout(any(), any(), any(), anyBoolean()))
            .thenReturn(new LogoutResponse("success"));
    }

    // ═══════════════════════════════════════════════════════════════
    // ログインエンドポイントのテスト
    // ═══════════════════════════════════════════════════════════════

    @Test
    @WithMockUser
    void 認証済みユーザーがログインエンドポイントにアクセスするとフロントエンドにリダイレクト() throws Exception {
        mockMvc.perform(get("/bff/auth/login"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost:5173/auth-callback"));
    }

    @Test
    @WithMockUser
    void return_toパラメータがある場合クエリパラメータとして付与される() throws Exception {
        mockMvc.perform(get("/bff/auth/login")
                .param("return_to", "/my-reviews"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost:5173/auth-callback?return_to=%2Fmy-reviews"));
    }

    @Test
    @WithMockUser
    void Refererヘッダーからフロントエンドベースパスを抽出してリダイレクト() throws Exception {
        mockMvc.perform(get("/bff/auth/login")
                .header("Referer", "http://localhost:5173/my-books/reviews"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost:5173/my-books/auth-callback"));
    }

    // ═══════════════════════════════════════════════════════════════
    // ログアウトエンドポイントのテスト
    // ═══════════════════════════════════════════════════════════════

    @Test
    @WithMockUser
    void 通常ログアウトが成功する() throws Exception {
        mockMvc.perform(post("/bff/auth/logout")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("success"));
    }

    @Test
    @WithMockUser
    void 完全ログアウトが成功する() throws Exception {
        mockMvc.perform(post("/bff/auth/logout")
                .param("complete", "true")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("success"));
    }

    @Test
    @WithMockUser
    void ログアウト時にOIDCプロバイダー接続失敗でも警告を含むレスポンスが返る() throws Exception {
        // AuthServiceが警告メッセージを返す場合
        when(authService.logout(any(), any(), any(), anyBoolean()))
            .thenReturn(new LogoutResponse("success", "認証サーバーのログアウトに失敗しました。"));

        mockMvc.perform(post("/bff/auth/logout")
                .param("complete", "true")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("success"))
            .andExpect(jsonPath("$.warning").value("認証サーバーのログアウトに失敗しました。"));
    }

    // ═══════════════════════════════════════════════════════════════
    // セキュリティ検証のテスト
    // ═══════════════════════════════════════════════════════════════

    @Test
    @WithMockUser
    void 不正なOriginヘッダーの場合デフォルトURLにフォールバック() throws Exception {
        mockMvc.perform(get("/bff/auth/login")
                .header("Origin", "http://malicious-site.com"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("http://localhost:5173/auth-callback*"));
    }
}
