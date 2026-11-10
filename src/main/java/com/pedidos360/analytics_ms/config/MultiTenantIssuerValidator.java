package com.pedidos360.analytics_ms.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.regex.Pattern;

/**
 * La app registration esta configurada como MULTI-TENANT (+ cuentas personales
 * de Microsoft), por lo que el issuer del token varia segun de que tenant venga
 * cada usuario (o "9188040d-6c67-4c5b-b112-36a304b66dad" para cuentas personales).
 * No podemos validar contra UN issuer fijo como en single-tenant.
 *
 * En vez de eso, validamos:
 * 1. Que el issuer tenga el formato esperado de Azure AD v2.0.
 * 2. Que el tenant en la URL del issuer coincida con el claim "tid" del propio
 *    token (evita que alguien falsifique un issuer con un tid distinto al real).
 *
 * Este es el patron recomendado por Microsoft para APIs multi-tenant.
 */
public class MultiTenantIssuerValidator implements OAuth2TokenValidator<Jwt> {

    private static final Pattern ISSUER_PATTERN =
            Pattern.compile("^https://login\\.microsoftonline\\.com/([a-zA-Z0-9-]+)/v2\\.0$");

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String issuer = jwt.getIssuer() != null ? jwt.getIssuer().toString() : null;
        String tid = jwt.getClaimAsString("tid");

        if (issuer == null || tid == null) {
            return fallo("El token no tiene issuer o claim tid validos");
        }

        var matcher = ISSUER_PATTERN.matcher(issuer);
        if (!matcher.matches()) {
            return fallo("El issuer no tiene el formato esperado de Azure AD: " + issuer);
        }

        String tenantEnIssuer = matcher.group(1);
        if (!tenantEnIssuer.equals(tid)) {
            return fallo("El tenant del issuer no coincide con el claim tid del token");
        }

        return OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidatorResult fallo(String mensaje) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", mensaje, null));
    }
}
