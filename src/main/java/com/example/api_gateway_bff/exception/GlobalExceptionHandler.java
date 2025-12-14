package com.example.api_gateway_bff.exception;

import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.lang.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.api_gateway_bff.dto.ErrorResponse;

import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler({ RateLimitExceededException.class })
    public ResponseEntity<Object> handleRateLimitExceeded(RateLimitExceededException ex, WebRequest request) {
        log.warn("レート制限超過: {}", ex.getMessage());

        String path = request.getDescription(false).replace("uri=", "");
        ErrorResponse errorResponse = new ErrorResponse(
            "TOO_MANY_REQUESTS",
            ex.getMessage(),
            HttpStatus.TOO_MANY_REQUESTS.value(),
            path
        );

        return this.handleExceptionInternal(
            ex,
            errorResponse,
            new HttpHeaders(),
            HttpStatus.TOO_MANY_REQUESTS,
            request
        );
    }

    @ExceptionHandler({ UnauthorizedException.class })
    public ResponseEntity<Object> handleUnauthorized(UnauthorizedException ex, WebRequest request) {
        log.warn("認証エラー: {}", ex.getMessage());

        String path = request.getDescription(false).replace("uri=", "");
        ErrorResponse errorResponse = new ErrorResponse(
            "UNAUTHORIZED",
            ex.getMessage(),
            HttpStatus.UNAUTHORIZED.value(),
            path
        );
        return this.handleExceptionInternal(
            ex,
            errorResponse,
            new HttpHeaders(),
            HttpStatus.UNAUTHORIZED,
            request
        );
    }

    @ExceptionHandler({ WebClientResponseException.class })
    public ResponseEntity<Object> handleWebClientResponse(WebClientResponseException ex, WebRequest request) {
        log.error("認証サーバーとの通信エラー: {} - {}", ex.getStatusCode(), ex.getMessage(), ex);

        String path = request.getDescription(false).replace("uri=", "");
        String errorCode;
        String message;
        HttpStatus status;

        // 認証サーバーからのエラーレスポンスを分析
        if (ex.getStatusCode().is4xxClientError()) {
            errorCode = "IDP_CLIENT_ERROR";
            message = "認証サーバーとの通信でクライアントエラーが発生しました";
            status = HttpStatus.BAD_REQUEST;
        } else if (ex.getStatusCode().is5xxServerError()) {
            errorCode = "IDP_SERVER_ERROR";
            message = "認証サーバーで障害が発生しています。しばらく時間をおいて再試行してください";
            status = HttpStatus.SERVICE_UNAVAILABLE;
        } else {
            errorCode = "IDP_COMMUNICATION_ERROR";
            message = "認証サーバーとの通信でエラーが発生しました";
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        ErrorResponse errorResponse = new ErrorResponse(
            errorCode,
            message,
            status.value(),
            path
        );

        return this.handleExceptionInternal(
            ex,
            errorResponse,
            new HttpHeaders(),
            status,
            request
        );
    }

    @ExceptionHandler({ WebClientException.class })
    public ResponseEntity<Object> handleWebClient(WebClientException ex, WebRequest request) {
        log.error("認証サーバーへの接続エラー: {}", ex.getMessage(), ex);

        String path = request.getDescription(false).replace("uri=", "");
        ErrorResponse errorResponse = new ErrorResponse(
            "IDP_CONNECTION_ERROR",
            "認証サーバーに接続できませんでした。ネットワーク接続を確認してください",
            HttpStatus.SERVICE_UNAVAILABLE.value(),
            path
        );

        return this.handleExceptionInternal(
            ex,
            errorResponse,
            new HttpHeaders(),
            HttpStatus.SERVICE_UNAVAILABLE,
            request
        );
    }

    @ExceptionHandler({ Exception.class })
    public ResponseEntity<Object> handleGeneral(Exception ex, WebRequest request) {
        log.error("予期しないエラー: {}", ex.getMessage(), ex);

        ErrorResponse errorResponse = new ErrorResponse(
            "INTERNAL_SERVER_ERROR",
            "内部サーバーエラーが発生しました",
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            request.getDescription(false).replace("uri=", "")
        );

        return this.handleExceptionInternal(
            ex,
            errorResponse,
            new HttpHeaders(),
            HttpStatus.INTERNAL_SERVER_ERROR,
            request
        );
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        @NonNull MethodArgumentNotValidException ex,
        @NonNull HttpHeaders headers,
        @NonNull HttpStatusCode status,
        @NonNull WebRequest request
    ) {
        log.error("バリデーションエラー: {}", ex.getMessage(), ex);

        // 複数のフィールドエラーをまとめる
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        List<String> errorMessages = new ArrayList<>();
        for (final FieldError error : fieldErrors) {
            errorMessages.add(error.getField() + ": " + error.getDefaultMessage());
        }

        ErrorResponse errorResponse = new ErrorResponse(
            "VALIDATION_FAILED",
            String.join(",", errorMessages),
            HttpStatus.BAD_REQUEST.value(),
            request.getDescription(false).replace("uri=", "")
        );

        return this.handleExceptionInternal(
            ex,
            errorResponse,
            headers,
            HttpStatus.BAD_REQUEST,
            request
        );
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
        @NonNull Exception ex,
        @Nullable Object body,
        @NonNull HttpHeaders headers,
        @NonNull HttpStatusCode statusCode,
        @NonNull WebRequest request
    ) {
        // bodyがErrorResponseの場合はログ重複を避けるため、ここではログ出力しない
        if (!(body instanceof ErrorResponse)) {
            log.error("内部例外: {} - {}", statusCode, ex.getMessage(), ex);
        }

        // bodyがnullまたはErrorResponse以外の場合、統一されたErrorResponseを作成
        if (!(body instanceof ErrorResponse)) {
            String errorCode = "HTTP_" + statusCode.value();
            String message = getDefaultMessageForStatus(statusCode);
            String path = request.getDescription(false).replace("uri=", "");

            body = new ErrorResponse(errorCode, message, statusCode.value(), path);
        }

        return ResponseEntity.status(statusCode).headers(headers).body(body);
    }

    private String getDefaultMessageForStatus(HttpStatusCode status) {
        return switch (status.value()) {
        case 400 -> "不正なリクエストです";
        case 401 -> "認証が必要です";
        case 403 -> "アクセスが拒否されました";
        case 404 -> "リソースが見つかりません";
        case 405 -> "許可されていないメソッドです";
        case 429 -> "リクエスト数が制限を超えました";
        case 500 -> "内部サーバーエラーが発生しました";
        default -> "エラーが発生しました";
        };
    }
}