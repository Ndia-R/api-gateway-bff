package com.example.api_gateway_bff.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Redis設定クラス
 *
 * このクラスは空に見えますが、@EnableRedisHttpSessionアノテーションにより
 * Spring Session Data Redisを有効化する重要な役割を果たしています。
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800) // 30分
public class RedisConfig {
    /**
     * Spring Session Data Redisの設定
     *
     * @EnableRedisHttpSessionは以下を自動的に設定します:
     * - RedisでのHTTPセッション永続化
     * - セッションタイムアウト管理（30分 = 1800秒）
     * - SessionRepositoryやRedisOperationsなどの必要なBeanの自動登録
     * - セッションイベント（作成・削除・有効期限切れ）の処理
     *
     * カスタムBean（RedisTemplate、RedisConnectionFactory等）は不要です。
     * Spring Bootの自動設定により、application.ymlの設定に基づいて
     * 適切なBeanが自動的に作成されます。
     *
     * セッション情報はRedisに以下の形式で保存されます:
     * - Key: spring:session:sessions:{sessionId}
     * - Expiration: 1800秒（30分）
     */
}