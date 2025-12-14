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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@RestController
@RequestMapping("/bff/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    /**
     * ログインエンドポイント
     *
     * <p>このエンドポイントは認証が必要なため、Spring Securityによって保護されています。</p>
     * <ul>
     *   <li><b>未認証ユーザー</b>がアクセスすると、コントローラーに到達する前にSpring SecurityがOAuth2認証フロー（IdPログイン画面）へリダイレクトします。</li>
     *   <li><b>認証済みユーザー</b>がアクセスすると、このメソッドが実行され、フロントエンドの認証後コールバックページへリダイレクトされます。</li>
     * </ul>
     */
    @GetMapping("/login")
    public void login(HttpServletResponse response) throws IOException {
        // 認証済みのため、フロントエンドの認証後コールバックページにリダイレクト
        response.sendRedirect(frontendUrl + "/auth-callback");
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