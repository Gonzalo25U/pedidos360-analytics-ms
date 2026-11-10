package com.pedidos360.analytics_ms.dynamo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.List;

/**
 * Crea las 4 tablas si no existen (modo on-demand, sin capacidad que administrar).
 * Asi no hay que crearlas a mano en la consola.
 *
 *  ClickstreamEventos : PK usuarioId, SK ts#eventId   -> historial crudo + deduplicacion
 *  ProductoMetricas   : PK productoId                 -> contadores por producto (con DynamoDB Stream, lo usara el dashboard)
 *  UsuarioProducto    : PK usuarioId, SK productoId   -> afinidad (puntaje) de cada usuario con cada producto
 *  Coocurrencia       : PK productoId, SK otroProdId  -> "quien interactuo con A tambien interactuo con B"
 */
@Slf4j
@Component
public class TablasInicializador {

    private final DynamoDbClient dynamo;
    private final String tEventos, tProductos, tAfinidad, tCoocurrencia;

    public TablasInicializador(DynamoDbClient dynamo,
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

    @EventListener(ApplicationReadyEvent.class)
    public void crearTablasSiNoExisten() {
        crear(tEventos, "usuarioId", "sk", false);
        crear(tProductos, "productoId", null, true);   // con stream: alimentara el dashboard en tiempo real
        crear(tAfinidad, "usuarioId", "productoId", false);
        crear(tCoocurrencia, "productoId", "otroProductoId", false);
    }

    private void crear(String nombre, String pk, String sk, boolean conStream) {
        try {
            dynamo.describeTable(r -> r.tableName(nombre));
            log.info("Tabla DynamoDB '{}' ya existe", nombre);
            return;
        } catch (ResourceNotFoundException e) {
            // no existe: se crea abajo
        }
        List<AttributeDefinition> attrs = new java.util.ArrayList<>();
        List<KeySchemaElement> keys = new java.util.ArrayList<>();
        attrs.add(AttributeDefinition.builder().attributeName(pk).attributeType(ScalarAttributeType.S).build());
        keys.add(KeySchemaElement.builder().attributeName(pk).keyType(KeyType.HASH).build());
        if (sk != null) {
            attrs.add(AttributeDefinition.builder().attributeName(sk).attributeType(ScalarAttributeType.S).build());
            keys.add(KeySchemaElement.builder().attributeName(sk).keyType(KeyType.RANGE).build());
        }
        CreateTableRequest.Builder req = CreateTableRequest.builder()
                .tableName(nombre)
                .attributeDefinitions(attrs)
                .keySchema(keys)
                .billingMode(BillingMode.PAY_PER_REQUEST);
        if (conStream) {
            req.streamSpecification(StreamSpecification.builder()
                    .streamEnabled(true).streamViewType(StreamViewType.NEW_AND_OLD_IMAGES).build());
        }
        dynamo.createTable(req.build());
        dynamo.waiter().waitUntilTableExists(r -> r.tableName(nombre));
        log.info("Tabla DynamoDB '{}' creada", nombre);
    }
}
