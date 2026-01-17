package com.example.api_gateway_bff.util;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * フロントエンドURL関連のユーティリティクラス
 *
 * <p>マルチアプリケーション対応のため、Referer/OriginヘッダーからフロントエンドURLを抽出する
 * 共通処理を提供します。</p>
 *
 * <p>このクラスは以下の場所から使用されます：</p>
 * <ul>
 *   <li>{@code AuthController} - 認証済みユーザーのリダイレクト先決定</li>
 *   <li>{@code CustomAuthorizationRequestResolver} - OAuth2認証フロー開始時のURL保存</li>
 * </ul>
 */
@Slf4j
public final class FrontendUrlUtils {

    private FrontendUrlUtils() {
        // ユーティリティクラスのためインスタンス化を禁止
    }

    /**
     * パスから最初のセグメント（アプリのベースパス）を抽出
     *
     * <h3>抽出例:</h3>
     * <pre>
     * /my-books/reviews → /my-books
     * /my-music/playlists → /my-music
     * /login → null (ルート直下)
     * </pre>
     *
     * @param path リクエストパス
     * @return アプリのベースパス（存在しない場合はnull）
     */
    public static String extractAppBasePath(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return null;
        }

        // 最初のスラッシュを除去してセグメントを取得
        String[] segments = path.substring(1).split("/");

        if (segments.length > 0 && !segments[0].isEmpty()) {
            // 特定のパス（BFF自身のエンドポイント）は除外
            String firstSegment = segments[0];
            if (firstSegment.equals("bff") || firstSegment.equals("api") ||
                firstSegment.equals("auth-callback")) {
                return null;
            }

            return "/" + firstSegment;
        }

        return null;
    }

    /**
     * Originが許可リストに含まれているか検証
     *
     * <p>CORS_ALLOWED_ORIGINSで指定されたオリジンのみを許可します。</p>
     * <p>ワイルドカード（*）に対応しています。</p>
     *
     * @param origin 検証対象のオリジン
     * @param corsAllowedOrigins カンマ区切りの許可オリジンリスト
     * @return 許可されている場合はtrue
     */
    public static boolean isOriginAllowed(String origin, String corsAllowedOrigins) {
        if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
            log.warn("CORS_ALLOWED_ORIGINS not configured, rejecting origin: {}", origin);
            return false;
        }

        String[] allowedOrigins = corsAllowedOrigins.split(",");
        for (String allowed : allowedOrigins) {
            allowed = allowed.trim();
            // ワイルドカード対応（例: https://localhost:*）
            if (origin.matches(allowed.replace("*", ".*").replace(".", "\\."))) {
                return true;
            }
        }

        log.warn("Origin not allowed: {}", origin);
        return false;
    }

    /**
     * RefererヘッダーからフロントエンドのベースURLを抽出
     *
     * <p>マルチアプリケーション対応のため、Refererからスキーム、ホスト、アプリベースパスを抽出します。</p>
     *
     * <h3>動作例:</h3>
     * <pre>
     * Referer: https://app.example.com/my-books/reviews
     *   → https://app.example.com/my-books
     *
     * Referer: https://app.example.com/my-music/playlists
     *   → https://app.example.com/my-music
     * </pre>
     *
     * @param referer Refererヘッダーの値
     * @param corsAllowedOrigins カンマ区切りの許可オリジンリスト
     * @return フロントエンドのベースURL（抽出できない場合はnull）
     */
    public static String extractFrontendUrlFromReferer(String referer, String corsAllowedOrigins) {
        if (referer == null || referer.isBlank()) {
            return null;
        }

        try {
            URI refererUri = new URI(referer);
            String baseOrigin = refererUri.getScheme() + "://" + refererUri.getAuthority();

            if (isOriginAllowed(baseOrigin, corsAllowedOrigins)) {
                String appBasePath = extractAppBasePath(refererUri.getPath());
                String fullUrl = appBasePath != null ? baseOrigin + appBasePath : baseOrigin;
                log.debug("Extracted frontend URL from Referer: {}", fullUrl);
                return fullUrl;
            }
        } catch (URISyntaxException e) {
            log.warn("Invalid Referer: {}", referer);
        }

        return null;
    }
}
