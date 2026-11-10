package com.pedidos360.analytics_ms.mensajeria;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Copia local del contrato publicado por productos-ms / carrito-ms (y luego ms-pago).
 * tipo: PRODUCTO_VISTO | ITEM_AGREGADO | ITEM_ELIMINADO | COMPRA_APROBADA
 * ignoreUnknown: si un productor agrega campos nuevos, este consumidor no se rompe.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClickstreamEvent {
    private String eventId;
    private String tipo;
    private String usuarioId;
    private Long productoId;
    private String nombreProducto;
    private String categoria;
    private Integer cantidad;
    private BigDecimal precio;
    private String origen;
    private String timestamp;
}
