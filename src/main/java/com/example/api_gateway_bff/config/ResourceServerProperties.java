package com.example.api_gateway_bff.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * リソースサーバー設定プロパティ
 *
 * <p>application.ymlの {@code app.resource-servers} 配下の設定を読み込む。</p>
 * 
 * <h3>設計方針</h3>
 * <ul>
 *   <li>複数のリソースサーバーをパスベースでルーティング</li>
 *   <li>各サービスごとにURL・タイムアウト・パスプレフィックスを設定</li>
 *   <li>{@link ConfigurationProperties} で型安全に設定を読み込み</li>
 * </ul>
 *
 * <h3>設定例（application.yml）</h3>
 * <pre>
 * app:
 *   # フロントエンドURL
 *   frontend:
 *     url: ${FRONTEND_URL}
 *   # 複数のリソースサーバー設定
 *   resource-servers:
 *     my-books:
 *       url: ${MY_BOOKS_SERVICE_URL}
 *       timeout: 30
 *       path-prefix: ${MY_BOOKS_SERVICE_PATH_PREFIX}
 *     my-musics:
 *       url: ${MY_MUSICS_SERVICE_URL}
 *       timeout: 30
 *       path-prefix: ${MY_MUSICS_SERVICE_PATH_PREFIX}
 * </pre>
 *
 * <h3>使用箇所</h3>
 * <ul>
 *   <li>{@link WebClientConfig}: サービスごとのWebClient作成</li>
 *   <li>{@link com.example.api_gateway_bff.controller.ApiProxyController}: ルーティング判定</li>
 * </ul>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class ResourceServerProperties {

    /**
     * リソースサーバー設定のマップ
     *
     * <p>キー: サービス名（my-books, my-musics等） </p>
     * <p>値: サービス設定（URL、タイムアウト、パスプレフィックス） </p>
     */
    private Map<String, ServerConfig> resourceServers = new HashMap<>();

    /**
     * 個別サーバーの設定
     */
    @Data
    public static class ServerConfig {
        /**
         * リソースサーバーのベースURL
         *
         * <p>例: http://my-books-api:8080 </p>
         */
        private String url;

        /**
         * タイムアウト設定（秒）
         *
         * <p>デフォルト: 30秒 </p>
         * <p>接続・読み込み・書き込みタイムアウトに適用 </p>
         */
        private int timeout = 30;

        /**
         * パスプレフィックス（ルーティング判定用）
         *
         * <p>例: /my-books </p>
         * <p>/api/my-books/** のリクエストをこのサービスに振り分ける </p>
         */
        private String pathPrefix;
    }
}
