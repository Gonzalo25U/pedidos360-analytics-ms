package com.pedidos360.analytics_ms.mensajeria;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Si procesar un evento falla (p. ej. DynamoDB no responde) se reintenta 5 veces,
 * 1 s entre intentos, ANTES de avanzar al siguiente mensaje. Asi un fallo transitorio
 * no pierde datos, pero un mensaje "venenoso" tampoco bloquea la particion para siempre.
 */
@Configuration
public class KafkaErrorConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, 5));
    }
}
