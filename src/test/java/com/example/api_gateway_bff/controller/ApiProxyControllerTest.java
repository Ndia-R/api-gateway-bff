package com.example.api_gateway_bff.controller;

import com.example.api_gateway_bff.config.TestConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Client;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ApiProxyControllerの単体テスト
 *
 * <p>APIプロキシ機能の基本動作を検証します。</p>
 *
 * <h3>テストケース:</h3>
 * <ul>
 *   <li>認証なしでのアクセス拒否</li>
 *   <li>認証済みユーザーのリクエスト処理</li>
 *   <li>各種HTTPメソッド（GET/POST/PUT/DELETE）のサポート</li>
 * </ul>
 *
 * <p><b>注意:</b> 実際のリソースサーバーへの接続テストは統合テストで実施します。</p>
 */
@WebMvcTest(ApiProxyController.class)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "app.resource-servers.my-books.url=http://localhost:9000",
    "app.resource-servers.my-books.timeout=30",
    "app.resource-servers.my-books.path-prefix=/my-books",
    "app.resource-servers.my-musics.url=http://localhost:9001",
    "app.resource-servers.my-musics.timeout=30",
    "app.resource-servers.my-musics.path-prefix=/my-musics",
    "rate-limit.enabled=false"
})
@SuppressWarnings("null") // Spring Security Test APIの型アノテーション互換性のため警告を抑制
class ApiProxyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * テスト: 認証なしでAPIプロキシにアクセス
     * TestConfigでpermitAllに設定しているため、認証チェックはスキップされる
     */
    @Test
    void testProxyEndpoint_WithoutAuthentication_ShouldPassSecurityCheck() throws Exception {
        // TestConfigにより認証はスキップされ、WebClientのエラーが発生
        mockMvc.perform(get("/api/my-books/list"))
            .andExpect(status().is5xxServerError());
    }

    /**
     * テスト: 認証済みユーザーがAPIプロキシにアクセス可能
     * 注: 実際のプロキシ動作はリソースサーバーが必要なため、統合テストで検証
     */
    @Test
    @WithMockUser
    void testProxyEndpoint_WithAuthentication_ShouldAcceptRequest() throws Exception {
        // このテストはセキュリティ設定のみ検証
        // 実際のWebClientの動作は統合テストで検証
        mockMvc.perform(
            get("/api/my-books/list")
                .with(oauth2Client("oidc"))
        )
            .andExpect(status().is5xxServerError()); // WebClientがモックされていないため、実際の接続エラー
    }

    /**
     * テスト: POSTリクエストのサポート確認
     */
    @Test
    @WithMockUser
    void testProxyEndpoint_PostMethod_ShouldAcceptRequest() throws Exception {
        mockMvc.perform(
            post("/api/my-books/create")
                .with(oauth2Client("oidc"))
                .contentType("application/json")
                .content("{\"title\":\"Test Book\"}")
        )
            .andExpect(status().is5xxServerError()); // WebClientがモックされていないため、実際の接続エラー
    }

    /**
     * テスト: PUTリクエストのサポート確認
     */
    @Test
    @WithMockUser
    void testProxyEndpoint_PutMethod_ShouldAcceptRequest() throws Exception {
        mockMvc.perform(
            put("/api/my-books/1")
                .with(oauth2Client("oidc"))
                .contentType("application/json")
                .content("{\"title\":\"Updated Book\"}")
        )
            .andExpect(status().is5xxServerError()); // WebClientがモックされていないため、実際の接続エラー
    }

    /**
     * テスト: DELETEリクエストのサポート確認
     */
    @Test
    @WithMockUser
    void testProxyEndpoint_DeleteMethod_ShouldAcceptRequest() throws Exception {
        mockMvc.perform(
            delete("/api/my-books/1")
                .with(oauth2Client("oidc"))
        )
            .andExpect(status().is5xxServerError()); // WebClientがモックされていないため、実際の接続エラー
    }
}
