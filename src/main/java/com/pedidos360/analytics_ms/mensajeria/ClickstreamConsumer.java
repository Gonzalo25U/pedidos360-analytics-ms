package com.pedidos360.analytics_ms.mensajeria;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.analytics_ms.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor del topic clickstream-eventos (grupo "analytics-group").
 * 3 hilos (spring.kafka.listener.concurrency) = 1 por particion.
 * Al estar la key = usuarioId, todos los eventos de un usuario llegan al mismo hilo y en orden.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickstreamConsumer {

    private final AnalyticsService analytics;
    private final ObjectMapper mapper = new ObjectMapper();

    @KafkaListener(topics = "${clickstream.topic}")
    public void consumir(ConsumerRecord<String, String> record) {
        ClickstreamEvent evento;
        try {
            evento = mapper.readValue(record.value(), ClickstreamEvent.class);
        } catch (Exception e) {
            // JSON invalido: reintentar no sirve de nada, se descarta y se sigue.
            log.error("Mensaje invalido descartado (particion={}, offset={}): {}",
                    record.partition(), record.offset(), e.getMessage());
            return;
        }
        if (evento.getEventId() == null) {
            evento.setEventId("k-" + record.partition() + "-" + record.offset());
        }
        if (evento.getUsuarioId() == null || evento.getProductoId() == null) {
            log.warn("Evento sin usuarioId/productoId ignorado: {}", record.value());
            return;
        }
        log.info("Evento recibido {} usuario={} producto={} (particion={}, offset={})",
                evento.getTipo(), evento.getUsuarioId(), evento.getProductoId(),
                record.partition(), record.offset());
        analytics.procesar(evento); // si lanza excepcion, KafkaErrorConfig reintenta
    }
}
