package com.example.api_gateway_bff.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * レート制限設定クラス
 *
 * <p>Bucket4j + Redisを使用した分散レート制限を実装します。</p>
 *
 * <h3>主な責務:</h3>
 * <ul>
 *   <li>Bucket4jのProxyManagerをRedisバックエンドで構築</li>
 *   <li>複数のBFFインスタンス間でレート制限状態を共有</li>
 *   <li>既存のRedisインフラストラクチャを活用</li>
 * </ul>
 *
 * <h3>設計方針:</h3>
 * <ul>
 *   <li><b>分散環境対応</b>: Redisでレート制限カウンターを共有し、複数インスタンス間で一貫性を保つ</li>
 *   <li><b>既存Redis活用</b>: セッション管理と同じRedisインスタンスを使用し、インフラコストを削減</li>
 *   <li><b>CAS (Compare-And-Swap)</b>: Lettuceベースの楽観的ロックで高いパフォーマンスを実現</li>
 * </ul>
 *
 * <h3>Redisキー構造:</h3>
 * <pre>
 * rate_limit:auth:{IPアドレス}     - 認証エンドポイントのレート制限
 * rate_limit:api:{セッションID}    - APIプロキシのレート制限
 * </pre>
 *
 * <h3>条件付き有効化:</h3>
 * <p>rate-limit.enabled=trueの場合のみこの設定が有効になります。</p>
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    /**
     * RedisClient Bean定義
     *
     * <p>Bucket4j用のRedisクライアントを作成します。</p>
     *
     * <h3>ライフサイクル管理:</h3>
     * <ul>
     *   <li>destroyMethod = "close" により、アプリケーション終了時に自動クローズ</li>
     *   <li>コネクションリークを防止</li>
     * </ul>
     *
     * @return Redis接続クライアント
     */
    @Bean(destroyMethod = "close")
    public RedisClient redisClient() {
        RedisURI redisUri = RedisURI.builder()
            .withHost(redisHost)
            .withPort(redisPort)
            .build();

        return RedisClient.create(redisUri);
    }

    /**
     * Bucket4j ProxyManagerのBean定義
     *
     * <p>このProxyManagerは、Redisをバックエンドストレージとして使用し、
     * 分散環境でのレート制限を実現します。</p>
     *
     * <h3>動作原理:</h3>
     * <ol>
     *   <li>リクエストごとにRedisから現在のトークン数を取得</li>
     *   <li>トークンを消費（減算）</li>
     *   <li>CAS（Compare-And-Swap）でRedisに書き込み</li>
     *   <li>競合が発生した場合は自動的にリトライ</li>
     * </ol>
     *
     * <h3>パフォーマンス:</h3>
     * <ul>
     *   <li>Lettuce（Nettyベース）による非同期I/O</li>
     *   <li>CASロケーションで同時アクセスに対応</li>
     *   <li>Redisへのラウンドトリップは1リクエストあたり1回</li>
     * </ul>
     *
     * @param redisClient Redis接続クライアント（Spring管理）
     * @return Redis連携のProxyManager
     */
    @Bean
    public ProxyManager<byte[]> proxyManager(RedisClient redisClient) {
        // LettuceベースのProxyManagerを構築
        // CAS (Compare-And-Swap) 方式で複数インスタンス間の競合を解決
        return LettuceBasedProxyManager.builderFor(redisClient)
            .withExpirationStrategy(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                    Duration.ofHours(1)
                )
            )
            .build();
    }
}
