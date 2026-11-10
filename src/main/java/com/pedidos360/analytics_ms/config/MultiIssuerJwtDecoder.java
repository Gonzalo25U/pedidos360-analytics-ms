package com.pedidos360.analytics_ms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Permite que el microservicio acepte tokens de DOS proveedores de identidad
 * distintos: Azure AD y AWS Cognito (agregado como extra - ver enunciado de
 * la fase 2). Primero lee el claim "iss" del token SIN verificar todavia su
 * firma (solo para decidir a cual de los 2 decoders reales pertenece), y
 * delega ahi - ese decoder real es quien de verdad valida firma, vigencia,
 * issuer y audience.
 *
 * Leer el issuer sin verificar la firma es seguro aqui porque NO se toma
 * ninguna decision de autorizacion con ese valor - solo decide cual validador
 * completo aplicar despues. Si alguien falsificara el issuer, el decoder
 * real correspondiente igual rechazaria la firma invalida.
 */
public class MultiIssuerJwtDecoder implements JwtDecoder {

    private final JwtDecoder azureDecoder;
    private final JwtDecoder cognitoDecoder;
    private final String cognitoIssuer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MultiIssuerJwtDecoder(JwtDecoder azureDecoder, JwtDecoder cognitoDecoder, String cognitoIssuer) {
        this.azureDecoder = azureDecoder;
        this.cognitoDecoder = cognitoDecoder;
        this.cognitoIssuer = cognitoIssuer;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String issuer = extraerIssuerSinVerificar(token);

        if (issuer == null) {
            throw new BadJwtException("No se pudo leer el issuer del token");
        }
        if (issuer.equals(cognitoIssuer)) {
            return cognitoDecoder.decode(token);
        }
        if (issuer.contains("login.microsoftonline.com")) {
            return azureDecoder.decode(token);
        }
        throw new BadJwtException("Issuer no reconocido: " + issuer);
    }

    private String extraerIssuerSinVerificar(String token) {
        try {
            String[] partes = token.split("\\.");
            String payloadJson = new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8);
            Map<?, ?> claims = objectMapper.readValue(payloadJson, Map.class);
            Object iss = claims.get("iss");
            return iss != null ? iss.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
