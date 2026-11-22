package br.pucrs.pulsepass.emissao.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.pucrs.pulsepass.common.EventEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/** US25 (e-mail com os ingressos), RNF05 (sem e-mail duplicado), RNF11 (SMTP fora) e RNF15. */
@SpringBootTest
class NotificacaoIT {

  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
  static final GenericContainer<?> MAILPIT =
      new GenericContainer<>("axllent/mailpit:v1.21.8")
          .withExposedPorts(1025, 8025)
          .waitingFor(Wait.forListeningPorts(1025, 8025));

  static {
    Startables.deepStart(PG, KAFKA, MAILPIT).join();
  }

  @DynamicPropertySource
  static void propriedades(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("spring.mail.host", MAILPIT::getHost);
    r.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    r.add("pulsepass.outbox.poll-ms", () -> "200");
    r.add("emissao.notificacao.poll-ms", () -> "200");
    r.add("emissao.notificacao.recuo-ms", () -> "400");
    r.add("emissao.notificacao.max-tentativas", () -> "5");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired ObjectMapper mapper;
  @Autowired JavaMailSenderImpl smtp;

  private final HttpClient http = HttpClient.newHttpClient();

  private JsonNode mailpit(String caminho) throws Exception {
    var resposta =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://"
                            + MAILPIT.getHost()
                            + ":"
                            + MAILPIT.getMappedPort(8025)
                            + caminho))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return mapper.readTree(resposta.body());
  }

  private JsonNode emailsPara(String endereco) throws Exception {
    return mailpit(
            "/api/v1/search?query=" + URLEncoder.encode("to:" + endereco, StandardCharsets.UTF_8))
        .path("messages");
  }

  /** PedidoPago com 1 assento e 2 unidades de lote: 3 ingressos. */
  private String pedidoPago(UUID pedido, UUID eventId, String email) throws Exception {
    var itens =
        List.of(
            Map.of(
                "assentoId",
                UUID.randomUUID(),
                "loteId",
                UUID.randomUUID(),
                "quantidade",
                1,
                "precoUnitario",
                "250.00",
                "descricao",
                "Setor A, assento 1"),
            Map.of(
                "loteId",
                UUID.randomUUID(),
                "quantidade",
                2,
                "precoUnitario",
                "100.00",
                "descricao",
                "Pista"));
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("pedidoId", pedido);
    payload.put("reservaId", UUID.randomUUID());
    payload.put("clienteId", "cliente-" + pedido);
    payload.put("eventoId", UUID.randomUUID());
    payload.put("eventoNome", "Festival Teste");
    payload.put("itens", itens);
    payload.put("total", "450.00");
    payload.put("pagoEm", Instant.now());
    payload.put("clienteEmail", email);
    var envelope =
        new EventEnvelope(
            eventId,
            "PedidoPago",
            Instant.now(),
            pedido.toString(),
            1,
            mapper.valueToTree(payload));
    String json = mapper.writeValueAsString(envelope);
    kafka.send("checkout.pedido.v1", pedido.toString(), json).get();
    return json;
  }

  private int ingressos(UUID pedido) {
    return jdbc.queryForObject(
        "select count(*) from ingresso where pedido_id = ?", Integer.class, pedido);
  }

  private Map<String, Object> notificacao(UUID pedido) {
    return jdbc.queryForMap(
        "select status, tentativas, destinatario, erro from notificacao where pedido_id = ?",
        pedido);
  }

  private <T> T comSmtpForaDoAr(java.util.concurrent.Callable<T> corpo) throws Exception {
    int original = smtp.getPort();
    smtp.setPort(1); // porta fechada
    try {
      return corpo.call();
    } finally {
      smtp.setPort(original);
    }
  }

  @Test
  void emailChegaComUmPdfPorIngressoEOEnderecoSomeDepoisDoEnvio() throws Exception {
    UUID pedido = UUID.randomUUID();
    String email = "ana-" + pedido + "@exemplo.test";
    pedidoPago(pedido, UUID.randomUUID(), email);

    await().atMost(Duration.ofSeconds(30)).until(() -> emailsPara(email).size() == 1);

    var resumo = emailsPara(email).get(0);
    assertThat(resumo.path("Subject").asText())
        .isEqualTo("Seus ingressos PulsePass - Festival Teste");
    var detalhe = mailpit("/api/v1/message/" + resumo.path("ID").asText());
    var anexos = detalhe.path("Attachments");
    assertThat(anexos).hasSize(3);
    List<String> nomes = new ArrayList<>();
    for (JsonNode a : anexos) {
      assertThat(a.path("ContentType").asText()).isEqualTo("application/pdf");
      nomes.add(a.path("FileName").asText());
    }
    List<String> esperados =
        jdbc
            .queryForList("select id from ingresso where pedido_id = ?", UUID.class, pedido)
            .stream()
            .map(id -> "ingresso-" + id + ".pdf")
            .toList();
    assertThat(nomes).containsExactlyInAnyOrderElementsOf(esperados);
    // o anexo é mesmo um PDF
    var bytes =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://"
                            + MAILPIT.getHost()
                            + ":"
                            + MAILPIT.getMappedPort(8025)
                            + "/api/v1/message/"
                            + resumo.path("ID").asText()
                            + "/part/"
                            + anexos.get(0).path("PartID").asText()))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(new String(bytes.body(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(notificacao(pedido).get("status")).isEqualTo("ENVIADA"));
    var n = notificacao(pedido);
    assertThat(n.get("destinatario")).as("endereço apagado após o envio").isNull();
    assertThat(n.get("tentativas")).isEqualTo(1);
  }

  @Test
  void reentregaDoEventoNaoMandaDoisEmails() throws Exception {
    UUID pedido = UUID.randomUUID();
    String email = "bia-" + pedido + "@exemplo.test";
    UUID mesmoEvento = UUID.randomUUID();
    pedidoPago(pedido, mesmoEvento, email);
    pedidoPago(pedido, mesmoEvento, email); // o Kafka entregou duas vezes
    pedidoPago(pedido, UUID.randomUUID(), email); // outro eventId para o mesmo pedido

    await().atMost(Duration.ofSeconds(30)).until(() -> emailsPara(email).size() == 1);
    Thread.sleep(2500); // tempo para um segundo e-mail aparecer, se houvesse

    assertThat(emailsPara(email)).hasSize(1);
    assertThat(ingressos(pedido)).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from notificacao where pedido_id = ?", Integer.class, pedido))
        .isEqualTo(1);
  }

  @Test
  void smtpForaDoArNaoImpedeOIngressoEOEmailSaiQuandoVolta() throws Exception {
    UUID pedido = UUID.randomUUID();
    String email = "caio-" + pedido + "@exemplo.test";

    comSmtpForaDoAr(
        () -> {
          pedidoPago(pedido, UUID.randomUUID(), email);
          await().atMost(Duration.ofSeconds(30)).until(() -> ingressos(pedido) == 3);
          await()
              .atMost(Duration.ofSeconds(30))
              .until(() -> ((Number) notificacao(pedido).get("tentativas")).intValue() >= 1);
          // RNF11: o ingresso já existe, e o e-mail continua na fila
          assertThat(notificacao(pedido).get("status")).isEqualTo("PENDENTE");
          assertThat(emailsPara(email)).isEmpty();
          return null;
        });

    await().atMost(Duration.ofSeconds(30)).until(() -> emailsPara(email).size() == 1);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(notificacao(pedido).get("status")).isEqualTo("ENVIADA"));
    assertThat(((Number) notificacao(pedido).get("tentativas")).intValue())
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void depoisDeTodasAsTentativasMarcaFalhaEApagaOEndereco() throws Exception {
    UUID pedido = UUID.randomUUID();
    String email = "duda-" + pedido + "@exemplo.test";

    comSmtpForaDoAr(
        () -> {
          pedidoPago(pedido, UUID.randomUUID(), email);
          await()
              .atMost(Duration.ofSeconds(40))
              .until(() -> "FALHA".equals(notificacao(pedido).get("status")));
          return null;
        });

    var n = notificacao(pedido);
    assertThat(n.get("tentativas")).isEqualTo(5);
    assertThat(n.get("destinatario")).as("endereço apagado também na falha").isNull();
    assertThat((String) n.get("erro")).isNotBlank().doesNotContain(email);
    assertThat(ingressos(pedido)).isEqualTo(3);
    Thread.sleep(1500);
    assertThat(emailsPara(email)).isEmpty(); // não tenta mais depois de desistir
  }

  @Test
  void semEmailNoEventoNaoCriaNotificacaoEOIngressoSaiNormalmente() throws Exception {
    UUID pedido = UUID.randomUUID();
    pedidoPago(pedido, UUID.randomUUID(), null);

    await().atMost(Duration.ofSeconds(30)).until(() -> ingressos(pedido) == 3);

    assertThat(
            jdbc.queryForObject(
                "select count(*) from notificacao where pedido_id = ?", Integer.class, pedido))
        .isZero();
  }
}
