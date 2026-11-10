package com.pedidos360.analytics_ms.controller;

import com.pedidos360.analytics_ms.dto.RecomendacionDTO;
import com.pedidos360.analytics_ms.service.RecomendacionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Rutas expuestas por el API Gateway:
 *  GET /api/recomendaciones?limite=5  -> recomendaciones para el usuario del token (cualquier autenticado)
 *  GET /api/analytics/resumen         -> metricas globales (solo Admin)
 * El usuario SIEMPRE sale del JWT (claim "sub"), nunca de un parametro.
 */
@RestController
@RequiredArgsConstructor
public class RecomendacionController {

    private final RecomendacionService service;

    @GetMapping("/api/recomendaciones")
    public ResponseEntity<List<RecomendacionDTO>> recomendaciones(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "5") int limite) {
        int tope = Math.max(1, Math.min(limite, 20));
        return ResponseEntity.ok(service.recomendar(jwt.getSubject(), tope));
    }

    @GetMapping("/api/analytics/resumen")
    public ResponseEntity<Map<String, Object>> resumen() {
        return ResponseEntity.ok(service.resumen());
    }
}
