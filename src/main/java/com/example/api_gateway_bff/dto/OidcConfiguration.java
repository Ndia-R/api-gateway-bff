package com.example.api_gateway_bff.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * OIDC Discoveryエンドポイントから取得するメタデータを格納するDTO
 *
 * <p>end_session_endpointなど、必要なプロパティのみを定義する。</p>
 */
@Data
public class OidcConfiguration {

    /**
     * OpenID Connect RP-Initiated LogoutのためのエンドポイントURL
     *
     * <p>このURLは {@code /.well-known/openid-configuration} から自動取得されます。
     * IDプロバイダーごとに異なるパス構造を持ちます。</p>
     *
     * <p>例（Keycloak）:</p>
     * <pre>http://localhost:8080/realms/my-realm/protocol/openid-connect/logout</pre>
     *
     * <p>例（Auth0）:</p>
     * <pre>https://your-tenant.auth0.com/v2/logout</pre>
     *
     * <p>例（Okta）:</p>
     * <pre>https://dev-12345678.okta.com/oauth2/default/v1/logout</pre>
     */
    @JsonProperty("end_session_endpoint")
    private String endSessionEndpoint;

}
