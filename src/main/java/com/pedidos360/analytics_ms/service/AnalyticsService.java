package com.pedidos360.analytics_ms.service;

import com.pedidos360.analytics_ms.mensajeria.ClickstreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Procesa cada evento de clickstream y actualiza 4 tablas de DynamoDB:
 *  1. ClickstreamEventos : guarda el evento crudo (y DEDUPLICA por eventId)
 *  2. ProductoMetricas   : contadores globales por producto
 *  3. UsuarioProducto    : afinidad del usuario con el producto (puntaje acumulado)
 *  4. Coocurrencia       : cuando un usuario se interesa por un producto NUEVO, se suma
 *                          1 entre ese producto y cada otro producto que ya le interesaba.
 *
 * Pesos de afinidad: vista=1, agregado al carrito=3, eliminado del carrito=-2, compra=5.
 */
@Slf4j
@Service
public class AnalyticsService {

    private final DynamoDbClient dynamo;
    private final String tEventos, tProductos, tAfinidad, tCoocurrencia;

    public AnalyticsService(DynamoDbClient dynamo,
                            @Value("${dynamodb.tabla-eventos}") String tEventos,
                            @Value("${dynamodb.tabla-productos}") String tProductos,
                            @Value("${dynamodb.tabla-afinidad}") String tAfinidad,
                            @Value("${dynamodb.tabla-coocurrencia}") String tCoocurrencia) {
        this.dynamo = dynamo;
        this.tEventos = tEventos;
        this.tProductos = tProductos;
        this.tAfinidad = tAfinidad;
        this.tCoocurrencia = tCoocurrencia;
    }

    public void procesar(ClickstreamEvent e) {
        int peso = peso(e.getTipo());
        if (peso == 0 && !"ITEM_ELIMINADO".equals(e.getTipo())) {
            log.warn("Tipo de evento desconocido ignorado: {}", e.getTipo());
            return;
        }
        String productoId = String.valueOf(e.getProductoId());

        // 1) Evento crudo + deduplicacion. Si Kafka reentrega el mismo evento (at-least-once),
        //    el putItem condicional falla y NO se vuelve a contar nada.
        if (!guardarEventoCrudo(e, productoId)) {
            log.info("Evento {} duplicado, se ignora", e.getEventId());
            return;
        }

        // 2) Metricas globales del producto
        actualizarMetricasProducto(e, productoId, peso);

        // 3) Afinidad usuario-producto (devuelve el puntaje nuevo)
        double nuevo = actualizarAfinidad(e, productoId, peso);
        double anterior = nuevo - peso;

        // 4) Co-ocurrencia: solo cuando el producto pasa a ser "de interes" por primera vez
        if (nuevo > 0 && anterior <= 0) {
            actualizarCoocurrencia(e.getUsuarioId(), productoId);
        }
    }

    static int peso(String tipo) {
        if (tipo == null) return 0;
        return switch (tipo) {
            case "PRODUCTO_VISTO" -> 1;
            case "ITEM_AGREGADO" -> 3;
            case "ITEM_ELIMINADO" -> -2;
            case "COMPRA_APROBADA" -> 5;
            default -> 0;
        };
    }

    private boolean guardarEventoCrudo(ClickstreamEvent e, String productoId) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("usuarioId", s(e.getUsuarioId()));
        item.put("sk", s(e.getTimestamp() + "#" + e.getEventId()));
        item.put("eventId", s(e.getEventId()));
        item.put("tipo", s(e.getTipo()));
        item.put("productoId", s(productoId));
        item.put("nombreProducto", s(e.getNombreProducto() == null ? "" : e.getNombreProducto()));
        item.put("origen", s(e.getOrigen() == null ? "" : e.getOrigen()));
        if (e.getPrecio() != null) item.put("precio", n(e.getPrecio().toPlainString()));
        if (e.getCantidad() != null) item.put("cantidad", n(String.valueOf(e.getCantidad())));
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(tEventos)
                    .item(item)
                    .conditionExpression("attribute_not_exists(usuarioId)")
                    .build());
            return true;
        } catch (ConditionalCheckFailedException dup) {
            return false;
        }
    }

    private void actualizarMetricasProducto(ClickstreamEvent e, String productoId, int peso) {
        int vistas = "PRODUCTO_VISTO".equals(e.getTipo()) ? 1 : 0;
        int agregados = "ITEM_AGREGADO".equals(e.getTipo()) ? 1 : 0;
        int eliminados = "ITEM_ELIMINADO".equals(e.getTipo()) ? 1 : 0;
        int compras = "COMPRA_APROBADA".equals(e.getTipo()) ? 1 : 0;

        Map<String, String> nombres = Map.of(
                "#v", "vistas", "#a", "agregados", "#e", "eliminados", "#c", "compras",
                "#sc", "score", "#n", "nombre", "#p", "precio", "#t", "ultimoEvento");
        Map<String, AttributeValue> valores = new HashMap<>();
        valores.put(":v", n(String.valueOf(vistas)));
        valores.put(":a", n(String.valueOf(agregados)));
        valores.put(":e", n(String.valueOf(eliminados)));
        valores.put(":c", n(String.valueOf(compras)));
        valores.put(":sc", n(String.valueOf(peso)));
        valores.put(":n", s(e.getNombreProducto() == null ? "" : e.getNombreProducto()));
        valores.put(":p", n(e.getPrecio() == null ? "0" : e.getPrecio().toPlainString()));
        valores.put(":t", s(e.getTimestamp() == null ? "" : e.getTimestamp()));

        dynamo.updateItem(UpdateItemRequest.builder()
                .tableName(tProductos)
                .key(Map.of("productoId", s(productoId)))
                .updateExpression("ADD #v :v, #a :a, #e :e, #c :c, #sc :sc SET #n = :n, #p = :p, #t = :t")
                .expressionAttributeNames(nombres)
                .expressionAttributeValues(valores)
                .build());
    }

    private double actualizarAfinidad(ClickstreamEvent e, String productoId, int peso) {
        Map<String, AttributeValue> valores = new HashMap<>();
        valores.put(":w", n(String.valueOf(peso)));
        valores.put(":n", s(e.getNombreProducto() == null ? "" : e.getNombreProducto()));
        valores.put(":p", n(e.getPrecio() == null ? "0" : e.getPrecio().toPlainString()));

        UpdateItemResponse r = dynamo.updateItem(UpdateItemRequest.builder()
                .tableName(tAfinidad)
                .key(Map.of("usuarioId", s(e.getUsuarioId()), "productoId", s(productoId)))
                .updateExpression("ADD #sc :w SET #n = :n, #p = :p")
                .expressionAttributeNames(Map.of("#sc", "score", "#n", "nombre", "#p", "precio"))
                .expressionAttributeValues(valores)
                .returnValues(ReturnValue.ALL_NEW)
                .build());
        return Double.parseDouble(r.attributes().get("score").n());
    }

    private void actualizarCoocurrencia(String usuarioId, String productoId) {
        QueryResponse q = dynamo.query(QueryRequest.builder()
                .tableName(tAfinidad)
                .keyConditionExpression("usuarioId = :u")
                .expressionAttributeValues(Map.of(":u", s(usuarioId)))
                .limit(50)
                .build());
        for (Map<String, AttributeValue> otro : q.items()) {
            String otroId = otro.get("productoId").s();
            double score = Double.parseDouble(otro.get("score").n());
            if (otroId.equals(productoId) || score <= 0) continue;
            sumarPar(productoId, otroId);
            sumarPar(otroId, productoId);
        }
    }

    private void sumarPar(String a, String b) {
        dynamo.updateItem(UpdateItemRequest.builder()
                .tableName(tCoocurrencia)
                .key(Map.of("productoId", s(a), "otroProductoId", s(b)))
                .updateExpression("ADD #c :uno")
                .expressionAttributeNames(Map.of("#c", "conteo"))
                .expressionAttributeValues(Map.of(":uno", n("1")))
                .build());
    }

    static AttributeValue s(String v) { return AttributeValue.builder().s(v).build(); }
    static AttributeValue n(String v) { return AttributeValue.builder().n(v).build(); }
}
