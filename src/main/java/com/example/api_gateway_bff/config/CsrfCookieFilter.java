package com.example.api_gateway_bff.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * CSRFトークンを確実にCookieに設定するフィルター
 *
 * Spring Security 6.x では、CookieCsrfTokenRepository を使用しても
 * トークンが自動的にCookieに設定されない場合があるため、
 * このフィルターで明示的にトークンをロードしてレスポンスに含める。
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // CSRFトークンを取得（存在しない場合は新規生成される）
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());

        if (csrfToken != null) {
            // トークンの値を取得することで、Cookieへのセットをトリガーする
            csrfToken.getToken();
        }

        filterChain.doFilter(request, response);
    }
}
