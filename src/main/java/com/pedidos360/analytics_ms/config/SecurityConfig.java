package com.pedidos360.analytics_ms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Configuracion de seguridad REAL. Valida JWT de DOS proveedores de
 * identidad: Azure AD (el principal del proyecto) y AWS Cognito (agregado
 * como extra para la fase 2) - ver MultiIssuerJwtDecoder para el detalle de
 * como se decide cual usar segun el token recibido.
 *
 * Solo se activa cuando el perfil "local" NO esta activo, para no exigir
 * credenciales reales mientras se prueban otras partes del sistema (ver
 * LocalSecurityConfig).
 *
 * Doble validacion (defensa en profundidad):
 * - AWS API Gateway valida firma/issuer/audience/vigencia en el borde (JWT Authorizer).
 * - Este microservicio VUELVE a validarlo de forma independiente.
 *
 * Autorizacion: GET -> cualquier usuario autenticado. POST/PUT/DELETE -> solo rol Admin.
 * El rol Admin de Cognito (grupo "Admin") se mapea al mismo ROLE_Admin que
 * usa Azure AD (ver extractAuthorities) - el resto del codigo no distingue
 * de que proveedor vino el usuario.
 */
/**
 * Misma validacion JWT dual (Azure AD + Cognito) que el resto de los microservicios.
 * Este ms es de SOLO LECTURA hacia afuera: el unico trafico de escritura entra por Kafka.
 */
@Configuration
@EnableWebSecurity
@Profile("!local")
public class SecurityConfig {

    @Value("${azure.audience}")
    private String expectedAudience;

    @Value("${azure.client-id}")
    private String clientId;

    @Value("${cognito.issuer}")
    private String cognitoIssuer;

    @Value("${cognito.client-id}")
    private String cognitoClientId;

    @Value("${cors.allowed-origins}")
    private String allowedOrigin;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                // Recomendaciones: cualquier usuario autenticado, SOLO las suyas (el usuario sale del JWT).
                .requestMatchers(HttpMethod.GET, "/api/recomendaciones/**").authenticated()
                // Metricas globales (para el dashboard): solo Admin.
                .requestMatchers(HttpMethod.GET, "/api/analytics/**").hasRole("Admin")
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
                .authenticationEntryPoint((request, response, ex) ->
                    response.sendError(401, "No autorizado: token ausente o invalido"))
                .accessDeniedHandler((request, response, ex) ->
                    response.sendError(403, "Prohibido: no cuenta con el rol requerido"))
            );

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        // --- Decoder de Azure AD (igual que siempre) ---
        // Multi-tenant: usamos el JWKS compartido de /common (valido para
        // cualquier tenant de Azure AD y cuentas personales de Microsoft).
        NimbusJwtDecoder azureDecoder = NimbusJwtDecoder
                .withJwkSetUri("https://login.microsoftonline.com/common/discovery/v2.0/keys")
                .build();
        OAuth2TokenValidator<Jwt> azureValidators = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new MultiTenantIssuerValidator(),
                // Acepta tanto "api://<client-id>" como el client-id sin prefijo.
                new AudienceValidator(List.of(expectedAudience, clientId))
        );
        azureDecoder.setJwtValidator(azureValidators);

        // --- Decoder de Cognito (nuevo) ---
        // Cognito SI tiene un issuer fijo (no es multi-tenant como Azure), asi
        // que JwtDecoders.fromIssuerLocation resuelve su JWKS automaticamente
        // via el documento de descubrimiento OIDC de ese user pool especifico.
        NimbusJwtDecoder cognitoDecoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(cognitoIssuer);
        OAuth2TokenValidator<Jwt> cognitoValidators = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(cognitoIssuer), // timestamp + issuer exacto
                new CognitoAudienceValidator(cognitoClientId)
        );
        cognitoDecoder.setJwtValidator(cognitoValidators);

        return new MultiIssuerJwtDecoder(azureDecoder, cognitoDecoder, cognitoIssuer);
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::extractAuthorities);
        return converter;
    }

    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();

        // Roles (Azure App Roles) y Groups (Cognito) -> el mismo prefijo ROLE_,
        // asi el resto del codigo (hasRole("Admin")) nunca necesita distinguir
        // de que proveedor vino el usuario.
        agregarComoRoles(jwt.getClaimAsStringList("roles"), authorities);
        agregarComoRoles(jwt.getClaimAsStringList("cognito:groups"), authorities);

        // Scopes: Azure usa el claim "scp", Cognito usa "scope" (ambos son un
        // string separado por espacios, solo cambia el nombre del claim).
        agregarScopes(jwt.getClaimAsString("scp"), authorities);
        agregarScopes(jwt.getClaimAsString("scope"), authorities);

        return authorities;
    }

    private void agregarComoRoles(List<String> valores, Collection<GrantedAuthority> authorities) {
        if (valores == null) return;
        valores.forEach(v -> authorities.add(new SimpleGrantedAuthority("ROLE_" + v)));
    }

    private void agregarScopes(String scopesCrudos, Collection<GrantedAuthority> authorities) {
        if (scopesCrudos == null) return;
        for (String scope : scopesCrudos.split(" ")) {
            if (!scope.isBlank()) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
            }
        }
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
