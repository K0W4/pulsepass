package br.pucrs.pulsepass.emissao.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucrs.pulsepass.common.EventEnvelope;
import br.pucrs.pulsepass.emissao.service.Eventos.Item;
import br.pucrs.pulsepass.emissao.service.Eventos.PedidoPago;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

/** RNF05 (emissão idempotente), RNF14 (QR assinado) e US28/US29 (validação na portaria). */
@SpringBootTest
@AutoConfigureMockMvc
class EmissaoIT {

  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

  static {
    Startables.deepStart(PG, KAFKA).join();
  }

  @DynamicPropertySource
  static void propriedades(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("pulsepass.outbox.poll-ms", () -> "200");
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired ObjectMapper mapper;

  private RequestPostProcessor cliente(String id) {
    return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("ROLE_CLIENTE"));
  }

  private RequestPostProcessor operador(String id) {
    return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"));
  }

  /** Publica PedidoPago com 1 assento e 2 unidades de lote (3 ingressos). Devolve o pedidoId. */
  private UUID pedidoPago(String clienteId, boolean duplicar) throws Exception {
    UUID pedido = UUID.randomUUID();
    var evento =
        new PedidoPago(
            pedido,
            UUID.randomUUID(),
            clienteId,
            UUID.randomUUID(),
            "Show da Emissão",
            List.of(
                new Item(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    new BigDecimal("250.00"),
                    "Setor A, assento 7"),
                new Item(null, UUID.randomUUID(), 2, new BigDecimal("100.00"), "Lote Pista")),
            new BigDecimal("450.00"),
            Instant.now());
    var env =
        new EventEnvelope(
            UUID.randomUUID(),
            "PedidoPago",
            Instant.now(),
            pedido.toString(),
            1,
            mapper.valueToTree(evento));
    String json = mapper.writeValueAsString(env);
    kafka.send("checkout.pedido.v1", pedido.toString(), json).get();
    if (duplicar) {
      kafka.send("checkout.pedido.v1", pedido.toString(), json).get();
    }
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> contar("select count(*) from ingresso where pedido_id = ?", pedido) == 3);
    return pedido;
  }

  private int contar(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private JsonNode ingressos(String clienteId) throws Exception {
    String corpo =
        mvc.perform(get("/ingressos").with(cliente(clienteId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(corpo);
  }

  @Test
  void pedidoPagoEmiteUmIngressoPorUnidadeEReprocessarNaoDuplica() throws Exception {
    UUID pedido = pedidoPago("c-emissao", true);
    Thread.sleep(1500); // dá tempo de a duplicata ser consumida

    assertThat(contar("select count(*) from ingresso where pedido_id = ?", pedido)).isEqualTo(3);
    assertThat(
            contar(
                "select count(*) from outbox_event where msg_key = ? and body like '%IngressoEmitido%'",
                pedido.toString()))
        .isEqualTo(3);
    var lista = ingressos("c-emissao");
    assertThat(lista).hasSize(3);
    lista.forEach(i -> assertThat(i.get("codigo").asText()).startsWith("PP1."));
    mvc.perform(get("/ingressos").with(cliente("outro")))
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void pdfDoIngressoTemMagicNumberECarimboEApenasODonoBaixa() throws Exception {
    pedidoPago("c-pdf", false);
    String id = ingressos("c-pdf").get(0).get("id").asText();

    byte[] pdf =
        mvc.perform(get("/ingressos/" + id + "/pdf").with(cliente("c-pdf")))
            .andExpect(status().isOk())
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                    .contentType(MediaType.APPLICATION_PDF))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    assertThat(pdf.length).isGreaterThan(1000);
    try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
      String texto = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
      assertThat(texto).contains("Show da Emiss").contains("Emitido em:").contains(id);
    }
    mvc.perform(get("/ingressos/" + id + "/pdf").with(cliente("intruso")))
        .andExpect(status().isNotFound());
  }

  @Test
  void primeiraLeituraLiberaESegundaDizJaUtilizadoComOHorario() throws Exception {
    pedidoPago("c-portaria", false);
    String codigo = ingressos("c-portaria").get(0).get("codigo").asText();
    String corpo = "{\"codigo\":\"" + codigo + "\"}";

    mvc.perform(
            post("/validacoes")
                .with(operador("op-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resultado").value("LIBERADO"))
        .andExpect(jsonPath("$.mensagem").value("Entrada liberada"))
        .andExpect(jsonPath("$.evento").value("Show da Emissão"));
    mvc.perform(
            post("/validacoes")
                .with(operador("op-2"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.resultado").value("JA_UTILIZADO"))
        .andExpect(jsonPath("$.mensagem").value("Ingresso já utilizado"))
        .andExpect(jsonPath("$.primeiraLeitura").exists());

    assertThat(
            contar(
                "select count(*) from validacao_acesso where resultado = 'LIBERADO' and ingresso_id::text = ?",
                codigo.split("\\.")[1]))
        .isEqualTo(1);
    assertThat(
            contar(
                "select count(*) from outbox_event where body like '%IngressoUtilizado%' and body like ?",
                "%" + codigo.split("\\.")[1] + "%"))
        .isEqualTo(1);
  }

  @Test
  void qrAlteradoEhRejeitadoSemTocarNoIngresso() throws Exception {
    pedidoPago("c-forja", false);
    String codigo = ingressos("c-forja").get(0).get("codigo").asText();
    String adulterado = codigo.substring(0, codigo.length() - 3) + "AAA";

    mvc.perform(
            post("/validacoes")
                .with(operador("op-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"codigo\":\"" + adulterado + "\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.resultado").value("INVALIDO"));
    assertThat(
            jdbc.queryForObject(
                "select status from ingresso where id = ?::uuid",
                String.class,
                codigo.split("\\.")[1]))
        .isEqualTo("EMITIDO");
  }

  @Test
  void cinquentaLeiturasSimultaneasDoMesmoQrLiberamExatamenteUma() throws Exception {
    pedidoPago("c-conc", false);
    String codigo = ingressos("c-conc").get(0).get("codigo").asText();
    String corpo = "{\"codigo\":\"" + codigo + "\"}";
    ExecutorService pool = Executors.newFixedThreadPool(50);
    CountDownLatch largada = new CountDownLatch(1);
    List<Future<Integer>> resultados = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      int n = i;
      resultados.add(
          pool.submit(
              () -> {
                largada.await();
                return mvc.perform(
                        post("/validacoes")
                            .with(operador("op-" + n))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(corpo))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              }));
    }
    largada.countDown();
    int liberados = 0;
    int recusados = 0;
    for (var f : resultados) {
      int s = f.get();
      if (s == 200) liberados++;
      if (s == 409) recusados++;
    }
    pool.shutdown();

    assertThat(liberados).as("LIBERADO").isEqualTo(1);
    assertThat(recusados).as("JA_UTILIZADO").isEqualTo(49);
  }

  @Test
  void clienteNaoValidaEOperadorNaoListaIngressos() throws Exception {
    mvc.perform(
            post("/validacoes")
                .with(cliente("c-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"codigo\":\"x\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/ingressos").with(operador("op-1"))).andExpect(status().isForbidden());
    mvc.perform(post("/validacoes").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }
}
