package br.pucrs.pulsepass.inventario.integration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/** PostgreSQL, Redis e Kafka reais, iniciados uma vez e compartilhados pelas classes de teste. */
abstract class ContainersBase {

  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

  static {
    Startables.deepStart(PG, REDIS, KAFKA).join();
  }

  @DynamicPropertySource
  static void propriedades(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("pulsepass.outbox.poll-ms", () -> "200");
    r.add("pulsepass.expiracao.poll-ms", () -> "300");
  }

  /** Lê um tópico desde o início até o prazo, para conferir o que o Outbox publicou. */
  static List<ConsumerRecord<String, String>> consumir(String topico, Duration prazo) {
    var props =
        Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "teste-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
    List<ConsumerRecord<String, String>> lidos = new ArrayList<>();
    try (var consumidor = new KafkaConsumer<String, String>(props)) {
      consumidor.subscribe(List.of(topico));
      long fim = System.nanoTime() + prazo.toNanos();
      while (System.nanoTime() < fim) {
        consumidor.poll(Duration.ofMillis(300)).forEach(lidos::add);
      }
    }
    return lidos;
  }
}
