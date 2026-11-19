package com.example.demo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers for integration tests. Image versions are pinned (not {@code :latest}) so runs are
 * reproducible; Redis matches {@code docker-compose.infra.yml}. Kafka uses {@code apache/kafka-native}, the image
 * family Testcontainers' {@link KafkaContainer} supports.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	static final String KAFKA_IMAGE = "apache/kafka-native:3.8.0";
	static final String REDIS_IMAGE = "redis:7-alpine";

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE))
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
	DynamicPropertyRegistrar redisDatabaseIsolation(GenericContainer<?> redisContainer) {
		return registry -> {
			String forkNumber = System.getProperty("surefire.forkNumber", "0");
			registry.add("spring.data.redis.database", () -> forkNumber);
		};
	}
}
