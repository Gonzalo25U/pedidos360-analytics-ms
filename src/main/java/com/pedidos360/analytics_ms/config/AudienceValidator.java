package com.pedidos360.analytics_ms.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;

/**
 * Spring Security valida por defecto la firma, el issuer y la vigencia (exp/nbf) del JWT,
 * pero NO valida el claim "aud" (audience) de forma automatica. Este validador cierra
 * ese hueco, exigido explicitamente por los requerimientos de seguridad del proyecto.
 *
 * NOTA: acepta CUALQUIERA de las audiences validas recibidas (normalmente
 * "api://<client-id>" y el client-id "pelado"), porque Azure AD tiene una
 * limitacion conocida: con cuentas personales de Microsoft (MSA), el token
 * a veces trae el aud como el client-id sin el prefijo api://, en vez del
 * Application ID URI completo. Ambas formas identifican el mismo recurso.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final Collection<String> expectedAudiences;

    public AudienceValidator(Collection<String> expectedAudiences) {
        this.expectedAudiences = expectedAudiences;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(expectedAudiences::contains)) {
            return OAuth2TokenValidatorResult.success();
        }
        OAuth2Error error = new OAuth2Error(
                "invalid_token",
                "El token no contiene ninguna audience esperada: " + expectedAudiences,
                null
        );
        return OAuth2TokenValidatorResult.failure(error);
    }
}
