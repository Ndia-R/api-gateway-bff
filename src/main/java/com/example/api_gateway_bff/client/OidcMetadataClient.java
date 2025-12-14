package com.example.api_gateway_bff.client;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.api_gateway_bff.dto.OidcConfiguration;

/**
 * OIDCプロバイダーのメタデータを管理するクライアント
 *
 * <p>アプリケーション起動時にOIDC Discoveryエンドポイントからメタデータを取得し、
 * 必要なエンドポイントURL（例: end_session_endpoint）をキャッシュする。</p>
 */
@Slf4j
@Component
@Getter
@RequiredArgsConstructor
public class OidcMetadataClient {

    private final WebClient webClient;

    @Value("${idp.issuer-uri}")
    private String issuerUri;

    private String endSessionEndpoint;

    /**
     * アプリケーション起動後にOIDCメタデータを取得する。
     * PostConstructアノテーションにより、このBeanの初期化後に一度だけ実行される。
     */
    @PostConstruct
    public void fetchMetadata() {
        String discoveryUrl = issuerUri + "/.well-known/openid-configuration";

        try {
            OidcConfiguration config = webClient.get()
                .uri(discoveryUrl)
                .retrieve()
                .bodyToMono(OidcConfiguration.class)
                .block(); // 起動時に同期待ちして、必須データを取得する

            if (config != null && config.getEndSessionEndpoint() != null) {
                this.endSessionEndpoint = config.getEndSessionEndpoint();
            } else {
                log.error("Failed to fetch or parse end_session_endpoint from OIDC metadata.");
            }
        } catch (Exception e) {
            log.error("Error fetching OIDC metadata from {}. OIDC provider logout may not work.", discoveryUrl, e);
        }
    }
}
