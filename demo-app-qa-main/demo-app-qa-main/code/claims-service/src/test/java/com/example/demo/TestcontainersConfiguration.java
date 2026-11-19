package com.example.demo;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers for integration tests.
 *
 * <p>Image versions are pinned to match {@code docker-compose.infra.yml} so tests run against the same
 * versions as the real stack. Floating {@code :latest} tags made runs non-reproducible — e.g.
 * {@code postgres:latest} rejects legacy JVM timezone aliases such as {@code Asia/Calcutta}, which broke
 * context startup on machines in that timezone while production ({@code postgres:15-alpine}) accepts it.
 * Kafka uses {@code apache/kafka-native} because that is the image family Testcontainers' {@link KafkaContainer}
 * supports (compose uses Confluent's cp-kafka; both speak the same protocol).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	static final String KAFKA_IMAGE = "apache/kafka-native:3.8.0";
	static final String POSTGRES_IMAGE = "postgres:15-alpine";
	static final String REDIS_IMAGE = "redis:7-alpine";
	static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.5.2";

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE))
				.withReuse(true);
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
				.withReuse(true);
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
				.withExposedPorts(6379)
				.withCommand("redis-server", "--databases", "32")
				.withReuse(true);
	}

	@Bean
	KeycloakContainer keycloakContainer() {
		return new KeycloakContainer(KEYCLOAK_IMAGE)
				.withRealmImportFile("keycloak/demo-app-realm.json")
				.withReuse(true);
	}

	@Bean
	DynamicPropertyRegistrar keycloakProperties(KeycloakContainer keycloak) {
		return registry -> {
			var issuerUri = keycloak.getAuthServerUrl() + "/realms/demo-app";
			registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuerUri);
			registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
					() -> issuerUri + "/protocol/openid-connect/certs");
		};
	}

	@Bean
	DynamicPropertyRegistrar redisDatabaseIsolation(GenericContainer<?> redisContainer) {
		return registry -> {
			String forkNumber = System.getProperty("surefire.forkNumber", "0");
			registry.add("spring.data.redis.database", () -> forkNumber);
		};
	}
}
