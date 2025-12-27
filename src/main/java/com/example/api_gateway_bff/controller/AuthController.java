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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/bff/auth")
@RequiredArgsConstructor
@Slf4j
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
     * @param session HTTPセッション（returnToの保存に使用）
     * @param response HTTPレスポンス（リダイレクトに使用）
     */
    @GetMapping("/login")
    public void login(
        @RequestParam(required = false) String returnTo,
        HttpSession session,
        HttpServletResponse response
    ) throws IOException {
        log.debug("Authenticated user accessing /bff/auth/login");

        // フロントエンドから受け取った復帰先URLをセッションに保存
        // OAuth2認証フロー後、authenticationSuccessHandlerがこの値を使用する
        if (returnTo != null && !returnTo.isBlank()) {
            session.setAttribute("redirect_after_login", returnTo);
        }

        // 認証済みのため、フロントエンドの認証後コールバックページにリダイレクト
        // returnToがある場合はクエリパラメータとして付与
        String redirectUrl = frontendUrl + "/auth-callback";
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