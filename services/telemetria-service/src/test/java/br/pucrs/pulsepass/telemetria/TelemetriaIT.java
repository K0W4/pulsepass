package br.pucrs.pulsepass.telemetria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucrs.pulsepass.telemetria.service.PrometheusClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/** US32 (painel), US33 (operacional), RNF05 (sem contar duas vezes) e RNF08 (p95 da emissão). */
@SpringBootTest
@AutoConfigureMockMvc
class TelemetriaIT {

  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
  static final HttpServer PROMETHEUS;

  static {
    Startables.deepStart(PG, KAFKA).join();
    try {
      PROMETHEUS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    PROMETHEUS.createContext(
        "/api/v1/query",
        troca -> {
          String consulta =
              URLDecoder.decode(troca.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          String valor =
              consulta.contains("histogram_quantile")
                  ? "0.25"
                  : consulta.contains("lag") ? "12" : "0.5";
          String corpo =
              "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                  + "{\"metric\":{\"application\":\"checkout-service\"},\"value\":[1700000000,\""
                  + valor
                  + "\"]},"
                  + "{\"metric\":{\"application\":\"sem-dados\"},\"value\":[1700000000,\"NaN\"]}]}}";
          byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
          troca.getResponseHeaders().add("Content-Type", "application/json");
          troca.sendResponseHeaders(200, bytes.length);
          troca.getResponseBody().write(bytes);
          troca.close();
        });
    PROMETHEUS.start();
  }

  @DynamicPropertySource
  static void propriedades(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("pulsepass.outbox.poll-ms", () -> "200");
    r.add(
        "telemetria.prometheus-url", () -> "http://127.0.0.1:" + PROMETHEUS.getAddress().getPort());
  }

  @TestConfiguration
  static class Topicos {
    @Bean
    NewTopic t1() {
      return new NewTopic("inventario.reserva.v1", 1, (short) 1);
    }

    @Bean
    NewTopic t2() {
      return new NewTopic("checkout.pedido.v1", 1, (short) 1);
    }

    @Bean
    NewTopic t3() {
      return new NewTopic("emissao.ingresso.v1", 1, (short) 1);
    }

    @Bean
    NewTopic t4() {
      return new NewTopic("iam.usuario.v1", 1, (short) 1);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired ObjectMapper mapper;

  @BeforeEach
  void limpar() {
    jdbc.update("delete from metrica_minuto");
    jdbc.update("delete from emissao_tempo");
  }

  private static RequestPostProcessor papel(String papel) {
    return jwt().jwt(j -> j.subject("u")).authorities(new SimpleGrantedAuthority("ROLE_" + papel));
  }

  private String envelope(UUID eventId, String tipo, String payload) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\""
        + tipo
        + "\",\"occurredAt\":\""
        + Instant.now()
        + "\",\"aggregateId\":\"a\",\"version\":1,\"payload\":"
        + payload
        + "}";
  }

  private void enviar(String topico, String tipo, String payload) {
    enviar(topico, UUID.randomUUID(), tipo, payload);
  }

  private void enviar(String topico, UUID eventId, String tipo, String payload) {
    kafka.send(topico, UUID.randomUUID().toString(), envelope(eventId, tipo, payload));
  }

  private JsonNode json(String caminho, String papel) throws Exception {
    return mapper.readTree(
        mvc.perform(get(caminho).with(papel(papel)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private long total(String nome) throws Exception {
    return json("/telemetria/resumo?minutos=60", "ORGANIZADOR").path("totais").path(nome).asLong(0);
  }

  @Test
  void contaPorMinutoEReentregaNaoContaDuasVezes() throws Exception {
    UUID pedido = UUID.randomUUID();
    UUID repetido = UUID.randomUUID();
    String pago = "{\"pedidoId\":\"" + pedido + "\",\"pagoEm\":\"" + Instant.now() + "\"}";

    enviar(
        "inventario.reserva.v1", "ReservaCriada", "{\"reservaId\":\"" + UUID.randomUUID() + "\"}");
    enviar("inventario.reserva.v1", "ReservaExpirada", "{}");
    enviar("checkout.pedido.v1", repetido, "PedidoPago", pago);
    enviar("checkout.pedido.v1", repetido, "PedidoPago", pago); // o Kafka entregou duas vezes
    enviar("checkout.pedido.v1", "PedidoReservado", "{}"); // não é contado
    enviar("emissao.ingresso.v1", "IngressoEmitido", "{\"pedidoId\":\"" + pedido + "\"}");
    enviar("iam.usuario.v1", "OrganizadorAprovado", "{}");

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(total("reservas_criadas")).isEqualTo(1);
              assertThat(total("reservas_expiradas")).isEqualTo(1);
              assertThat(total("pedidos_pagos")).isEqualTo(1);
              assertThat(total("ingressos_emitidos")).isEqualTo(1);
              assertThat(total("organizadores_aprovados")).isEqualTo(1);
            });
    Thread.sleep(1500); // dá tempo de a duplicata chegar
    var resumo = json("/telemetria/resumo?minutos=60", "ADMIN");
    assertThat(resumo.path("totais").path("pedidos_pagos").asLong()).isEqualTo(1);
    assertThat(resumo.path("totais").has("PedidoReservado")).isFalse();
    assertThat(resumo.path("serie")).isNotEmpty();
    assertThat(resumo.path("serie").get(0).path("valores").has("reservas_criadas")).isTrue();
  }

  @Test
  void p95DaEmissaoFuncionaNasDuasOrdensDeChegadaEVirgaMetaQuandoEstoura() throws Exception {
    Instant base = Instant.now().minusSeconds(60);
    // 10 pedidos de 5 s; metade chega primeiro o PedidoPago, metade primeiro o IngressoEmitido
    for (int i = 0; i < 10; i++) {
      UUID p = UUID.randomUUID();
      String pago = "{\"pedidoId\":\"" + p + "\",\"pagoEm\":\"" + base + "\"}";
      String emitido = "{\"pedidoId\":\"" + p + "\",\"emitidoEm\":\"" + base.plusSeconds(5) + "\"}";
      if (i % 2 == 0) {
        enviar("checkout.pedido.v1", "PedidoPago", pago);
        enviar("emissao.ingresso.v1", "IngressoEmitido", emitido);
      } else {
        enviar("emissao.ingresso.v1", "IngressoEmitido", emitido);
        enviar("checkout.pedido.v1", "PedidoPago", pago);
      }
    }
    // um pedido com 3 ingressos: vale o último (8 s)
    UUID tres = UUID.randomUUID();
    enviar(
        "checkout.pedido.v1",
        "PedidoPago",
        "{\"pedidoId\":\"" + tres + "\",\"pagoEm\":\"" + base + "\"}");
    for (int s : new int[] {4, 8, 6}) {
      enviar(
          "emissao.ingresso.v1",
          "IngressoEmitido",
          "{\"pedidoId\":\"" + tres + "\",\"emitidoEm\":\"" + base.plusSeconds(s) + "\"}");
    }

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(json("/telemetria/emissao?minutos=60", "ADMIN").path("amostras").asInt())
                    .isEqualTo(11));
    var ok = json("/telemetria/emissao?minutos=60", "ADMIN");
    assertThat(ok.path("p50").asDouble()).isEqualTo(5.0);
    assertThat(ok.path("p95").asDouble()).isBetween(5.0, 8.0);
    assertThat(ok.path("metaSegundos").asDouble()).isEqualTo(30.0);
    assertThat(ok.path("atendeMeta").asBoolean()).isTrue();

    // três pedidos lentos (60 s) empurram o p95 para além da meta de 30 s
    for (int i = 0; i < 3; i++) {
      UUID p = UUID.randomUUID();
      enviar(
          "checkout.pedido.v1",
          "PedidoPago",
          "{\"pedidoId\":\"" + p + "\",\"pagoEm\":\"" + base + "\"}");
      enviar(
          "emissao.ingresso.v1",
          "IngressoEmitido",
          "{\"pedidoId\":\"" + p + "\",\"emitidoEm\":\"" + base.plusSeconds(60) + "\"}");
    }
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(json("/telemetria/emissao?minutos=60", "ADMIN").path("amostras").asInt())
                    .isEqualTo(14));
    var estourou = json("/telemetria/emissao?minutos=60", "ADMIN");
    assertThat(estourou.path("p95").asDouble()).isGreaterThan(30.0);
    assertThat(estourou.path("atendeMeta").asBoolean()).isFalse();
  }

  @Test
  void semAmostrasNaoAfirmaQueAMetaFoiAtendida() throws Exception {
    var vazio = json("/telemetria/emissao?minutos=60", "ADMIN");
    assertThat(vazio.path("amostras").asInt()).isZero();
    assertThat(vazio.path("p95").isNull()).isTrue();
    assertThat(vazio.path("atendeMeta").asBoolean()).isFalse();
  }

  @Test
  void navegacaoAnonimaPassaPeloOutboxEViraMetrica() throws Exception {
    for (String pagina : new String[] {"catalogo", "../../etc/passwd"}) {
      mvc.perform(
              post("/telemetria/navegacao")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"pagina\":\"" + pagina + "\"}"))
          .andExpect(status().isAccepted());
    }
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(total("paginas_vistas")).isEqualTo(2);
              assertThat(total("pagina.catalogo")).isEqualTo(1);
              assertThat(total("pagina.outra")).isEqualTo(1);
            });
  }

  @Test
  void rbacDasRotas() throws Exception {
    mvc.perform(get("/telemetria/resumo")).andExpect(status().isUnauthorized());
    mvc.perform(get("/telemetria/resumo").with(papel("CLIENTE"))).andExpect(status().isForbidden());
    mvc.perform(get("/telemetria/emissao").with(papel("OPERADOR")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/telemetria/resumo").with(papel("ORGANIZADOR"))).andExpect(status().isOk());
    mvc.perform(get("/telemetria/emissao").with(papel("ADMIN"))).andExpect(status().isOk());
    mvc.perform(get("/telemetria/operacional").with(papel("ORGANIZADOR")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/telemetria/operacional")).andExpect(status().isUnauthorized());
    mvc.perform(get("/telemetria/resumo?minutos=0").with(papel("ADMIN")))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/telemetria/resumo?minutos=1441").with(papel("ADMIN")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void operacionalLeLatenciaErrosELagDoPrometheusEIgnoraSemDados() throws Exception {
    mvc.perform(get("/telemetria/operacional").with(papel("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.disponivel").value(true))
        .andExpect(jsonPath("$.latenciaP95Segundos.checkout-service").value(0.25))
        .andExpect(jsonPath("$.errosPorSegundo.checkout-service").value(0.5))
        .andExpect(jsonPath("$.lagKafka.checkout-service").value(12.0))
        .andExpect(jsonPath("$.lagKafka['sem-dados']").doesNotExist());
  }

  @Test
  void prometheusForaDoArNaoQuebraOPainel() {
    var cliente = new PrometheusClient("http://127.0.0.1:1");
    assertThat(cliente.operacional()).containsEntry("disponivel", false).hasSize(1);
  }
}
