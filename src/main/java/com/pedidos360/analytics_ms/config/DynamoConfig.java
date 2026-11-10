package com.pedidos360.analytics_ms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Cliente de DynamoDB. NO lleva access keys: DefaultCredentialsProvider toma las
 * credenciales temporales del perfil de instancia (LabInstanceProfile / LabRole)
 * de la maquina EC2 donde corre el contenedor.
 */
@Configuration
public class DynamoConfig {

    @Bean
    public DynamoDbClient dynamoDbClient(@Value("${aws.region}") String region) {
        return DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
