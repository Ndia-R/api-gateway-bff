package com.example.api_gateway_bff.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * リソースサーバー設定プロパティ（環境変数ベースの動的読み込み対応）
 *
 * <p>環境変数から動的にリソースサーバー設定を読み込みます。</p>
 * <p>Dockerイメージを再ビルドせずに、環境変数のみでサービスを追加・変更できます。</p>
 *
 * <h3>設計方針</h3>
 * <ul>
 *   <li>複数のリソースサーバーをパスベースでルーティング</li>
 *   <li>環境変数のみで設定を管理（application.ymlに固定値なし）</li>
 *   <li>本番環境でDockerイメージ変更不要でサービス追加が可能</li>
 * </ul>
 *
 * <h3>環境変数の設定例</h3>
 * <pre>
 * # リソースサーバーのリスト（カンマ区切り）
 * RESOURCE_SERVERS=service-01,service-02
 *
 * # 各サービスの設定
 * SERVICE_01_URL=http://my-books-api:8080
 * SERVICE_01_PATH_PREFIX=/my-books
 *
 * SERVICE_02_URL=http://my-music-api:8080
 * SERVICE_02_PATH_PREFIX=/my-music
 * </pre>
 *
 * <h3>使用箇所</h3>
 * <ul>
 *   <li>{@link WebClientConfig}: サービスごとのWebClient作成</li>
 *   <li>{@link com.example.api_gateway_bff.controller.ApiProxyController}: ルーティング判定</li>
 * </ul>
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class ResourceServerProperties {

    private final Environment environment;

    /**
     * リソースサーバー設定のマップ
     *
     * <p>キー: サービス名（service-01, service-02等） </p>
     * <p>値: サービス設定（URL、タイムアウト、パスプレフィックス） </p>
     * <p>環境変数RESOURCE_SERVERSから自動的に読み込まれる </p>
     */
    private Map<String, ServerConfig> resourceServers = new HashMap<>();

    /**
     * 環境変数からリソースサーバー設定を動的に読み込む
     *
     * <p>RESOURCE_SERVERS環境変数で指定されたサービス名リストを読み込み、
     * 各サービスのURL、パスプレフィックス、タイムアウトを環境変数から取得します。</p>
     *
     * <p><b>動作:</b></p>
     * <ol>
     *   <li>RESOURCE_SERVERS環境変数をカンマで分割してサービス名リストを取得</li>
     *   <li>各サービス名に対して、SERVICE_xx_URL、SERVICE_xx_PATH_PREFIX、SERVICE_xx_TIMEOUTを読み込む</li>
     *   <li>URLまたはパスプレフィックスが未定義のサービスはスキップ</li>
     * </ol>
     */
    @PostConstruct
    public void loadFromEnvironment() {
        String resourceServersList = environment.getProperty("RESOURCE_SERVERS", "");

        if (resourceServersList == null || resourceServersList.isBlank()) {
            log.warn("RESOURCE_SERVERS environment variable is not set. No resource servers will be configured.");
            return;
        }

        String[] serviceNames = resourceServersList.split(",");
        log.info("Loading {} resource server(s) from environment: {}", serviceNames.length, resourceServersList);

        for (String serviceName : serviceNames) {
            serviceName = serviceName.trim();
            if (serviceName.isEmpty()) {
                continue;
            }

            // 環境変数キーを生成（例: SERVICE_01_URL）
            String serviceKey = serviceName.toUpperCase().replace("-", "_");
            String urlKey = serviceKey + "_URL";
            String pathPrefixKey = serviceKey + "_PATH_PREFIX";
            String timeoutKey = serviceKey + "_TIMEOUT";

            // 環境変数から値を取得
            String url = environment.getProperty(urlKey);
            String pathPrefix = environment.getProperty(pathPrefixKey);
            Integer timeout = environment.getProperty(timeoutKey, Integer.class, 30);

            // URLとパスプレフィックスが必須
            if (url == null || url.isBlank()) {
                log.warn("Skipping service '{}': {} is not set", serviceName, urlKey);
                continue;
            }
            if (pathPrefix == null || pathPrefix.isBlank()) {
                log.warn("Skipping service '{}': {} is not set", serviceName, pathPrefixKey);
                continue;
            }

            // ServerConfigを作成してマップに追加
            ServerConfig config = new ServerConfig();
            config.setUrl(url);
            config.setPathPrefix(pathPrefix);
            config.setTimeout(timeout);

            resourceServers.put(serviceName, config);
            log.info(
                "Loaded resource server '{}': url={}, pathPrefix={}, timeout={}s",
                serviceName,
                url,
                pathPrefix,
                timeout
            );
        }

        if (resourceServers.isEmpty()) {
            log.error("No valid resource servers configured. Please check your environment variables.");
        } else {
            log.info("Successfully configured {} resource server(s)", resourceServers.size());
        }
    }

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
