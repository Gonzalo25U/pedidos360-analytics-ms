package com.pedidos360.analytics_ms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class RecomendacionDTO {
    private Long productoId;
    private String nombre;
    private BigDecimal precio;
    private double puntaje;
    /** "SIMILARES" (usuarios con gustos parecidos) o "POPULAR" (mas interaccion global). */
    private String motivo;
}
