package com.example.api_gateway_bff.config;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.lettuce.core.RedisClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * テスト用設定クラス
 *
 * <p>テスト実行時に必要なBean定義を提供します。</p>
 */
@TestConfiguration
public class TestConfig {

    /**
     * テスト用SecurityFilterChain
     * すべてのリクエストを許可する設定
     */
    @Bean
    @Primary
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(
                authz -> authz
                    .anyRequest()
                    .permitAll()
            );
        return http.build();
    }

    /**
     * テスト用ResourceServerProperties
     * ApiProxyControllerのテストに必要
     */
    @Bean
    @Primary
    public ResourceServerProperties testResourceServerProperties() {
        ResourceServerProperties properties = new ResourceServerProperties();
        Map<String, ResourceServerProperties.ServerConfig> servers = new HashMap<>();

        // service-01 設定
        ResourceServerProperties.ServerConfig service01Config = new ResourceServerProperties.ServerConfig();
        service01Config.setUrl("http://localhost:9000");
        service01Config.setTimeout(30);
        service01Config.setPathPrefix("/service-01");
        servers.put("service-01", service01Config);

        // service-02 設定
        ResourceServerProperties.ServerConfig service02Config = new ResourceServerProperties.ServerConfig();
        service02Config.setUrl("http://localhost:9001");
        service02Config.setTimeout(30);
        service02Config.setPathPrefix("/service-02");
        servers.put("service-02", service02Config);

        properties.setResourceServers(servers);
        return properties;
    }

    /**
     * テスト用WebClientのMap
     * ApiProxyControllerが要求するMap<String, WebClient>を提供
     */
    @Bean
    @Primary
    public Map<String, WebClient> testWebClients() {
        Map<String, WebClient> webClients = new HashMap<>();
        WebClient webClient = WebClient.builder().build();
        webClients.put("service-01", webClient);
        webClients.put("service-02", webClient);
        return webClients;
    }

    /**
     * テスト用OAuth2AuthorizedClientRepository
     * ApiProxyControllerが要求する依存関係
     */
    @Bean
    @Primary
    public OAuth2AuthorizedClientRepository testOAuth2AuthorizedClientRepository() {
        return mock(OAuth2AuthorizedClientRepository.class);
    }

    /**
     * テスト用ClientRegistrationRepository
     * OAuth2関連のテストで必要
     */
    @Bean
    @Primary
    public ClientRegistrationRepository testClientRegistrationRepository() {
        return mock(ClientRegistrationRepository.class);
    }

    /**
     * テスト用RedisClient
     * モックとして使用（実際のRedis接続は不要）
     */
    @Bean
    @Primary
    public RedisClient testRedisClient() {
        // Mockitoでモック化（実際のRedis接続を回避）
        return mock(RedisClient.class);
    }

    /**
     * テスト用ProxyManager
     * モックとして使用（レート制限テストでは使用しない）
     *
     * <p>注意: rate-limit.enabled=falseの場合、このBeanは使用されません。
     * ただし、一部のテストでrate-limit.enabled=trueの可能性があるため、
     * 適切にモック化しておく必要があります。</p>
     */
    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public ProxyManager<byte[]> testProxyManager() {
        // ProxyManagerをモック化
        ProxyManager<byte[]> mock = mock(ProxyManager.class);

        // builder()メソッドが呼ばれた場合のモック動作を設定
        // (実際には使用されないが、念のため設定)
        when(mock.builder()).thenReturn(null);

        return mock;
    }
}