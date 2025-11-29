package com.example.auth_bff.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * WebClient設定クラス
 *
 * <p>
 * このクラスは、複数のリソースサーバーに対応するWebClientインスタンスを提供します。
 * </p>
 *
 * <h3>設計方針:</h3>
 * <ul>
 *   <li><b>サービスごとのWebClient</b>: 各リソースサーバーに最適化されたWebClientを作成</li>
 *   <li><b>コネクションプール最適化</b>: リソースリークを防ぎ、パフォーマンスを向上</li>
 *   <li><b>タイムアウト設定</b>: サービスごとに接続・読み込み・書き込みタイムアウトを設定</li>
 * </ul>
 *
 * <h3>使用場所:</h3>
 * <ul>
 *   <li>ApiProxyController: リソースサーバーへのプロキシリクエスト</li>
 *   <li>AuthService: IdPへのログアウトリクエスト（共通WebClient使用）</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final ResourceServerProperties resourceServerProperties;

    /**
     * サービスごとのWebClient Beanの定義
     *
     * <p>
     * application.ymlの {@code app.resource-servers} 設定から
     * 各サービス用のWebClientを作成し、Mapで返却します。
     * </p>
     *
     * <h3>各WebClientの設定:</h3>
     * <ul>
     *   <li><b>接続タイムアウト</b>: リソースサーバーへの接続確立までの時間</li>
     *   <li><b>読み込みタイムアウト</b>: レスポンス待機時間</li>
     *   <li><b>書き込みタイムアウト</b>: リクエスト送信時間</li>
     * </ul>
     *
     * <h3>パフォーマンス最適化:</h3>
     * <ul>
     *   <li>Reactor Nettyのコネクションプールを使用</li>
     *   <li>Keep-Alive接続で再利用</li>
     *   <li>サービスごとに独立したコネクションプール</li>
     * </ul>
     *
     * @return サービス名をキーとしたWebClientのMap
     */
    @Bean
    public Map<String, WebClient> webClients() {
        Map<String, WebClient> clients = new HashMap<>();

        resourceServerProperties.getResourceServers().forEach((serviceName, config) -> {
            // HttpClientの設定
            HttpClient httpClient = HttpClient.create()
                // 接続タイムアウト設定（ミリ秒）
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getTimeout() * 1000)
                // レスポンスタイムアウト設定
                .responseTimeout(Duration.ofSeconds(config.getTimeout()))
                // HTTPリダイレクト自動追跡を有効化（301, 302等を自動的に追跡）
                .followRedirect(true)
                // 接続確立後のタイムアウトハンドラー設定
                .doOnConnected(
                    conn -> conn
                        // 読み込みタイムアウト
                        .addHandlerLast(new ReadTimeoutHandler(config.getTimeout(), TimeUnit.SECONDS))
                        // 書き込みタイムアウト
                        .addHandlerLast(new WriteTimeoutHandler(config.getTimeout(), TimeUnit.SECONDS))
                );

            // WebClientをビルド（Reactor Netty型アノテーション互換性のため警告を抑制）
            @SuppressWarnings("null")
            ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);

            WebClient webClient = WebClient.builder().clientConnector(connector).build();
            clients.put(serviceName, webClient);
        });

        return clients;
    }

    /**
     * 共通WebClient Bean（AuthService用）
     *
     * <p>
     * IdPへのログアウトリクエストなど、リソースサーバー以外への
     * HTTPリクエストに使用する汎用WebClientです。
     * </p>
     *
     * <p>デフォルトタイムアウト: 30秒</p>
     *
     * @return 汎用WebClientインスタンス
     */
    @Bean
    public WebClient webClient() {
        int defaultTimeout = 30;

        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, defaultTimeout * 1000)
            .responseTimeout(Duration.ofSeconds(defaultTimeout))
            .followRedirect(true)
            .doOnConnected(
                conn -> conn
                    .addHandlerLast(new ReadTimeoutHandler(defaultTimeout, TimeUnit.SECONDS))
                    .addHandlerLast(new WriteTimeoutHandler(defaultTimeout, TimeUnit.SECONDS))
            );

        @SuppressWarnings("null")
        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);

        return WebClient.builder().clientConnector(connector).build();
    }
}