package com.example.api_gateway_bff.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Redis設定クラス
 *
 * <p>{@code @EnableRedisHttpSession}アノテーションにより、
 * Spring Session Data Redisが有効化されます。</p>
 *
 * <h3>自動設定される内容:</h3>
 * <ul>
 *   <li>RedisでのHTTPセッション永続化</li>
 *   <li>セッションタイムアウト管理（application.ymlの{@code spring.session.timeout}で設定）</li>
 *   <li>SessionRepositoryやRedisOperationsなどの必要なBeanの自動登録</li>
 *   <li>セッションイベント（作成・削除・有効期限切れ）の処理</li>
 * </ul>
 *
 * <h3>セッション保存形式:</h3>
 * <ul>
 *   <li>Key: {@code spring:session:sessions:{sessionId}}</li>
 *   <li>Expiration: 環境変数 SESSION_TIMEOUT で設定（デフォルト: 12時間）</li>
 * </ul>
 *
 * <p>カスタムBean（RedisTemplate、RedisConnectionFactory等）は不要です。
 * Spring Bootの自動設定により、application.ymlの設定に基づいて
 * 適切なBeanが自動的に作成されます。</p>
 *
 * <p>セッションタイムアウトは{@code spring.session.timeout}プロパティで
 * 環境変数から動的に設定されるため、アノテーションでの固定値指定は行いません。</p>
 */
@Configuration
@EnableRedisHttpSession
public class RedisConfig {
}