package com.example.api_gateway_bff.exception;

/**
 * レート制限超過例外
 *
 * <p>APIリクエストのレート制限を超えた場合にスローされる例外です。</p>
 *
 * <h3>使用箇所:</h3>
 * <ul>
 *   <li>{@link com.example.api_gateway_bff.filter.RateLimitFilter} - レート制限フィルター</li>
 * </ul>
 *
 * <h3>設計方針:</h3>
 * <ul>
 *   <li><b>統一的なエラーハンドリング</b>: GlobalExceptionHandlerで429エラーに変換</li>
 *   <li><b>シンプルな設計</b>: RuntimeExceptionを継承し、メッセージのみ保持</li>
 *   <li><b>WebRequest活用</b>: リクエストパスはWebRequestから取得（例外に持たせない）</li>
 * </ul>
 *
 * @see com.example.api_gateway_bff.exception.GlobalExceptionHandler
 * @see com.example.api_gateway_bff.filter.RateLimitFilter
 */
public class RateLimitExceededException extends RuntimeException {

    /**
     * コンストラクタ
     *
     * @param message エラーメッセージ
     */
    public RateLimitExceededException(String message) {
        super(message);
    }
}
