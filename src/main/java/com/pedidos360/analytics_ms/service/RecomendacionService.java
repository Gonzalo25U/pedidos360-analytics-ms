package com.pedidos360.analytics_ms.service;

import com.pedidos360.analytics_ms.dto.RecomendacionDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Recomendaciones basadas en el comportamiento del usuario (filtrado colaborativo simple):
 *  1. Toma los productos que MAS le interesan al usuario (afinidad alta: vio, agrego, compro).
 *  2. Para cada uno, busca en "Coocurrencia" que otros productos le interesaron a la gente
 *     que se intereso por ese mismo producto.
 *  3. Suma puntajes (coocurrencia x afinidad del usuario) y descarta lo que ya tiene en el carrito/compro.
 *  4. Si faltan resultados (usuario nuevo / "cold start"), completa con los mas populares.
 */
@Service
public class RecomendacionService {

    private static final double UMBRAL_YA_TIENE = 3; // agregado o comprado: no se recomienda de nuevo

    private final DynamoDbClient dynamo;
    private final String tProductos, tAfinidad, tCoocurrencia;

    public RecomendacionService(DynamoDbClient dynamo,
                                @Value("${dynamodb.tabla-productos}") String tProductos,
                                @Value("${dynamodb.tabla-afinidad}") String tAfinidad,
                                @Value("${dynamodb.tabla-coocurrencia}") String tCoocurrencia) {
        this.dynamo = dynamo;
        this.tProductos = tProductos;
        this.tAfinidad = tAfinidad;
        this.tCoocurrencia = tCoocurrencia;
    }

    public List<RecomendacionDTO> recomendar(String usuarioId, int limite) {
        // 1) afinidades del usuario
        Map<String, Double> mias = new HashMap<>();
        QueryResponse q = dynamo.query(QueryRequest.builder()
                .tableName(tAfinidad)
                .keyConditionExpression("usuarioId = :u")
                .expressionAttributeValues(Map.of(":u", AnalyticsService.s(usuarioId)))
                .limit(50)
                .build());
        for (Map<String, AttributeValue> it : q.items()) {
            mias.put(it.get("productoId").s(), Double.parseDouble(it.get("score").n()));
        }

        // 2) semillas = sus 5 productos de mayor afinidad positiva
        List<Map.Entry<String, Double>> semillas = mias.entrySet().stream()
                .filter(en -> en.getValue() > 0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5).toList();

        // 3) candidatos por coocurrencia
        Map<String, Double> candidatos = new LinkedHashMap<>();
        for (Map.Entry<String, Double> semilla : semillas) {
            QueryResponse co = dynamo.query(QueryRequest.builder()
                    .tableName(tCoocurrencia)
                    .keyConditionExpression("productoId = :p")
                    .expressionAttributeValues(Map.of(":p", AnalyticsService.s(semilla.getKey())))
                    .limit(30)
                    .build());
            for (Map<String, AttributeValue> it : co.items()) {
                String otro = it.get("otroProductoId").s();
                if (mias.getOrDefault(otro, 0.0) >= UMBRAL_YA_TIENE) continue;
                double conteo = Double.parseDouble(it.get("conteo").n());
                candidatos.merge(otro, conteo * semilla.getValue(), Double::sum);
            }
        }

        List<RecomendacionDTO> resultado = new ArrayList<>();
        Set<String> usados = new HashSet<>();
        candidatos.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limite)
                .forEach(en -> {
                    Map<String, AttributeValue> prod = obtenerProducto(en.getKey());
                    if (prod != null) {
                        resultado.add(aDto(en.getKey(), prod, en.getValue(), "SIMILARES"));
                        usados.add(en.getKey());
                    }
                });

        // 4) relleno por popularidad
        if (resultado.size() < limite) {
            List<Map<String, AttributeValue>> todos = new ArrayList<>();
            dynamo.scanPaginator(ScanRequest.builder().tableName(tProductos).build())
                    .items().forEach(todos::add);
            todos.stream()
                    .filter(p -> !usados.contains(p.get("productoId").s()))
                    .filter(p -> mias.getOrDefault(p.get("productoId").s(), 0.0) < UMBRAL_YA_TIENE)
                    .sorted((a, b) -> Double.compare(score(b), score(a)))
                    .limit(limite - resultado.size())
                    .forEach(p -> resultado.add(aDto(p.get("productoId").s(), p, score(p), "POPULAR")));
        }
        return resultado;
    }

    private Map<String, AttributeValue> obtenerProducto(String productoId) {
        GetItemResponse r = dynamo.getItem(GetItemRequest.builder()
                .tableName(tProductos)
                .key(Map.of("productoId", AnalyticsService.s(productoId)))
                .build());
        return r.hasItem() && !r.item().isEmpty() ? r.item() : null;
    }

    private static double score(Map<String, AttributeValue> p) {
        AttributeValue v = p.get("score");
        return v == null ? 0 : Double.parseDouble(v.n());
    }

    private static RecomendacionDTO aDto(String id, Map<String, AttributeValue> p, double puntaje, String motivo) {
        AttributeValue nombre = p.get("nombre");
        AttributeValue precio = p.get("precio");
        return new RecomendacionDTO(
                Long.valueOf(id),
                nombre == null ? "" : nombre.s(),
                precio == null ? BigDecimal.ZERO : new BigDecimal(precio.n()),
                puntaje,
                motivo);
    }

    /** Resumen global para el panel de administracion (y base del dashboard en tiempo real). */
    public Map<String, Object> resumen() {
        long vistas = 0, agregados = 0, eliminados = 0, compras = 0;
        List<Map<String, AttributeValue>> todos = new ArrayList<>();
        dynamo.scanPaginator(ScanRequest.builder().tableName(tProductos).build()).items().forEach(todos::add);
        for (Map<String, AttributeValue> p : todos) {
            vistas += num(p, "vistas");
            agregados += num(p, "agregados");
            eliminados += num(p, "eliminados");
            compras += num(p, "compras");
        }
        List<Map<String, Object>> top = todos.stream()
                .sorted((a, b) -> Double.compare(score(b), score(a)))
                .limit(5)
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("productoId", p.get("productoId").s());
                    m.put("nombre", p.get("nombre") == null ? "" : p.get("nombre").s());
                    m.put("score", score(p));
                    return m;
                }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vistas", vistas);
        out.put("agregadosAlCarrito", agregados);
        out.put("eliminadosDelCarrito", eliminados);
        out.put("compras", compras);
        out.put("topProductos", top);
        return out;
    }

    private static long num(Map<String, AttributeValue> p, String k) {
        AttributeValue v = p.get(k);
        return v == null ? 0 : (long) Double.parseDouble(v.n());
    }
}
