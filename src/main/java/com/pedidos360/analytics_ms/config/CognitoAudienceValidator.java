package com.pedidos360.analytics_ms.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Los access tokens de Cognito NO usan el claim estandar "aud" (ese solo
 * aparece en el ID token) - usan "client_id". Tambien se valida que sea
 * realmente un access token ("token_use" = "access"), para rechazar un ID
 * token si alguien intentara usarlo como si fuera uno de acceso.
 */
public class CognitoAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String expectedClientId;

    public CognitoAudienceValidator(String expectedClientId) {
        this.expectedClientId = expectedClientId;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String clientId = jwt.getClaimAsString("client_id");
        String tokenUse = jwt.getClaimAsString("token_use");

        if (!"access".equals(tokenUse)) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "Se esperaba un access token de Cognito, no un ID token", null));
        }
        if (!expectedClientId.equals(clientId)) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "El client_id del token de Cognito no coincide con el esperado", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
