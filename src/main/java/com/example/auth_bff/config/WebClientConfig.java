package com.example.auth_bff.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient設定クラス
 *
 * <p>このクラスは、アプリケーション全体で共有されるWebClientインスタンスを提供します。</p>
 *
 * <h3>設計方針:</h3>
 * <ul>
 *   <li><b>シングルトンパターン</b>: WebClientインスタンスを1つだけ作成し、全体で再利用</li>
 *   <li><b>コネクションプール最適化</b>: リソースリークを防ぎ、パフォーマンスを向上</li>
 *   <li><b>タイムアウト設定</b>: 接続・読み込み・書き込みタイムアウトを統一管理</li>
 * </ul>
 *
 * <h3>使用場所:</h3>
 * <ul>
 *   <li>ApiProxyController: リソースサーバーへのプロキシリクエスト</li>
 *   <li>AuthService: IdPへのログアウトリクエスト</li>
 * </ul>
 */
@Configuration
public class WebClientConfig {

    /**
     * リソースサーバーへのタイムアウト設定（秒）
     * デフォルト: 30秒
     */
    @Value("${app.resource-server.timeout:30}")
    private int resourceServerTimeout;

    /**
     * 共有WebClient Beanの定義
     *
     * <p>このWebClientは以下の設定を持ちます：</p>
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
     *   <li>リクエストごとにWebClientを作成しない（旧実装の問題を解決）</li>
     * </ul>
     *
     * @return 設定済みWebClientインスタンス
     */
    @Bean
    public WebClient webClient() {
        // HttpClientの設定
        HttpClient httpClient = HttpClient.create()
            // 接続タイムアウト設定（ミリ秒）
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, resourceServerTimeout * 1000)
            // レスポンスタイムアウト設定
            .responseTimeout(Duration.ofSeconds(resourceServerTimeout))
            // HTTPリダイレクト自動追跡を有効化（301, 302等を自動的に追跡）
            .followRedirect(true)
            // 接続確立後のタイムアウトハンドラー設定
            .doOnConnected(
                conn -> conn
                    // 読み込みタイムアウト
                    .addHandlerLast(new ReadTimeoutHandler(resourceServerTimeout, TimeUnit.SECONDS))
                    // 書き込みタイムアウト
                    .addHandlerLast(new WriteTimeoutHandler(resourceServerTimeout, TimeUnit.SECONDS))
            );

        // WebClientをビルド（Reactor Netty型アノテーション互換性のため警告を抑制）
        @SuppressWarnings("null")
        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);

        return WebClient.builder().clientConnector(connector).build();
    }
}