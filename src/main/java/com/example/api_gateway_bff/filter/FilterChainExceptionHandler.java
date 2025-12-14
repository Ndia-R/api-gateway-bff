package com.example.api_gateway_bff.filter;

import com.example.api_gateway_bff.dto.ErrorResponse;
import com.example.api_gateway_bff.exception.RateLimitExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * フィルターチェーン例外ハンドラー
 *
 * <p>Spring Securityのフィルターチェーン内で発生した例外を捕捉し、
 * GlobalExceptionHandlerと同じ形式でエラーレスポンスを返します。</p>
 *
 * <h3>設計方針:</h3>
 * <ul>
 *   <li><b>統一されたエラーレスポンス</b>: GlobalExceptionHandlerと同じErrorResponse形式を使用</li>
 *   <li><b>保守性</b>: エラーレスポンス生成ロジックを一元化</li>
 *   <li><b>一貫性</b>: フィルターチェーン内外で同じエラーハンドリング方式</li>
 * </ul>
 *
 * <h3>処理フロー:</h3>
 * <ol>
 *   <li>後続のフィルターチェーンを実行</li>
 *   <li>例外が発生した場合、この例外をキャッチ</li>
 *   <li>例外の種類に応じて適切なErrorResponseを生成</li>
 *   <li>HTTPステータスコードとJSONレスポンスを返却</li>
 * </ol>
 *
 * <h3>配置位置:</h3>
 * <p>Spring Securityフィルターチェーンの最初に配置し、
 * すべてのフィルターで発生した例外をキャッチできるようにします。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FilterChainExceptionHandler extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    /**
     * フィルター処理のメインロジック
     *
     * <p>後続のフィルターチェーンを実行し、例外が発生した場合は
     * 適切なエラーレスポンスを返します。</p>
     *
     * @param request HTTPリクエスト
     * @param response HTTPレスポンス
     * @param filterChain フィルターチェーン
     * @throws ServletException サーブレット例外
     * @throws IOException I/O例外
     */
    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            // 後続のフィルターチェーンを実行
            filterChain.doFilter(request, response);
        } catch (RateLimitExceededException ex) {
            // レート制限超過エラー
            handleRateLimitExceeded(request, response, ex);
        } catch (Exception ex) {
            // その他の予期しないエラー
            handleGenericException(request, response, ex);
        }
    }

    /**
     * レート制限超過例外の処理
     *
     * <p>GlobalExceptionHandlerと同じ形式でErrorResponseを生成します。</p>
     *
     * @param request HTTPリクエスト
     * @param response HTTPレスポンス
     * @param ex レート制限超過例外
     * @throws IOException レスポンス書き込みエラー
     */
    private void handleRateLimitExceeded(
        HttpServletRequest request,
        HttpServletResponse response,
        RateLimitExceededException ex
    ) throws IOException {
        log.warn("レート制限超過: {}", ex.getMessage());

        String path = request.getRequestURI();
        ErrorResponse errorResponse = new ErrorResponse(
            "TOO_MANY_REQUESTS",
            ex.getMessage(),
            HttpStatus.TOO_MANY_REQUESTS.value(),
            path
        );

        sendErrorResponse(response, HttpStatus.TOO_MANY_REQUESTS, errorResponse);
    }

    /**
     * 一般的な例外の処理
     *
     * <p>フィルターチェーン内で発生した予期しない例外を処理します。</p>
     *
     * @param request HTTPリクエスト
     * @param response HTTPレスポンス
     * @param ex 例外
     * @throws IOException レスポンス書き込みエラー
     */
    private void handleGenericException(
        HttpServletRequest request,
        HttpServletResponse response,
        Exception ex
    ) throws IOException {
        log.error("フィルターチェーン内で予期しないエラー: {}", ex.getMessage(), ex);

        String path = request.getRequestURI();
        ErrorResponse errorResponse = new ErrorResponse(
            "INTERNAL_SERVER_ERROR",
            "内部サーバーエラーが発生しました",
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            path
        );

        sendErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, errorResponse);
    }

    /**
     * エラーレスポンスの送信
     *
     * <p>HTTPステータスコードとJSON形式のErrorResponseを送信します。</p>
     *
     * @param response HTTPレスポンス
     * @param status HTTPステータス
     * @param errorResponse エラーレスポンス
     * @throws IOException レスポンス書き込みエラー
     */
    private void sendErrorResponse(
        HttpServletResponse response,
        HttpStatus status,
        ErrorResponse errorResponse
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String jsonResponse = objectMapper.writeValueAsString(errorResponse);
        response.getWriter().write(jsonResponse);
        response.getWriter().flush();
    }
}
