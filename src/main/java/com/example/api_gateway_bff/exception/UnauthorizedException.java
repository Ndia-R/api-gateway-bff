package com.example.api_gateway_bff.exception;

/**
 * 認証エラー例外
 *
 * <p>認証が必要なリソースに未認証でアクセスした場合にスローされる例外です。</p>
 *
 * <h3>使用箇所:</h3>
 * <ul>
 *   <li>{@link com.example.api_gateway_bff.service.AuthService} - 認証サービス</li>
 * </ul>
 *
 * <h3>設計方針:</h3>
 * <ul>
 *   <li><b>統一的なエラーハンドリング</b>: GlobalExceptionHandlerで401エラーに変換</li>
 *   <li><b>シンプルな設計</b>: RuntimeExceptionを継承し、メッセージのみ保持</li>
 *   <li><b>必要最小限の実装</b>: 実際に使用されるコンストラクタのみ定義</li>
 * </ul>
 *
 * @see com.example.api_gateway_bff.exception.GlobalExceptionHandler
 * @see com.example.api_gateway_bff.service.AuthService
 */
public class UnauthorizedException extends RuntimeException {

    /**
     * コンストラクタ
     *
     * @param message エラーメッセージ
     */
    public UnauthorizedException(String message) {
        super(message);
    }
}