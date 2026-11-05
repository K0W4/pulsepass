package br.pucrs.pulsepass.checkout.integration;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/** PostgreSQL e Kafka reais mais um gateway de pagamento falso com contador de chamadas. */
abstract class Stack {

  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

  /** modo: APROVAR, RECUSAR ou ERRO (500). */
  static volatile String modo = "APROVAR";

  static final AtomicInteger CHAMADAS = new AtomicInteger();
  static final HttpServer GATEWAY;

  static {
    Startables.deepStart(PG, KAFKA).join();
    try {
      GATEWAY = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    GATEWAY.createContext(
        "/cobrancas",
        troca -> {
          CHAMADAS.incrementAndGet();
          troca.getRequestBody().readAllBytes();
          String corpo;
          int codigo = 200;
          switch (modo) {
            case "ERRO" -> {
              codigo = 500;
              corpo = "{}";
            }
            case "RECUSAR" ->
                corpo =
                    "{\"status\":\"RECUSADO\",\"referencia\":\"r\",\"motivo\":\"SALDO_INSUFICIENTE\"}";
            default -> corpo = "{\"status\":\"APROVADO\",\"referencia\":\"r-ok\"}";
          }
          byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
          troca.getResponseHeaders().add("Content-Type", "application/json");
          troca.sendResponseHeaders(codigo, bytes.length);
          troca.getResponseBody().write(bytes);
          troca.close();
        });
    GATEWAY.start();
  }

  @DynamicPropertySource
  static void propriedades(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("pulsepass.outbox.poll-ms", () -> "200");
    r.add("pulsepass.gateway.url", () -> "http://127.0.0.1:" + GATEWAY.getAddress().getPort());
    r.add("pulsepass.gateway.timeout", () -> "PT3S");
    r.add("pulsepass.gateway.retry-backoff-ms", () -> "10");
  }
}
