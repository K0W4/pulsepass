package br.pucrs.pulsepass.checkout.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucrs.pulsepass.checkout.service.Eventos.Item;
import br.pucrs.pulsepass.checkout.service.Eventos.ReservaCriada;
import br.pucrs.pulsepass.common.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** RNF03 (idempotência), RNF05 (dedup), RNF10 (gateway) e a máquina de estados do pedido. */
@SpringBootTest
@AutoConfigureMockMvc
class CheckoutIT extends Stack {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired ObjectMapper mapper;

  @BeforeEach
  void gatewayNormal() {
    Stack.modo = "APROVAR";
    Stack.CHAMADAS.set(0);
  }

  private RequestPostProcessor cliente(String id) {
    return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("ROLE_CLIENTE"));
  }

  /** Publica ReservaCriada no Kafka (com dois itens que somam 500,00) e espera a projeção. */
  private UUID reservaPublicada(String clienteId) throws Exception {
    UUID reserva = UUID.randomUUID();
    var evento =
        new ReservaCriada(
            reserva,
            clienteId,
            UUID.randomUUID(),
            "Show de teste",
            List.of(
                new Item(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    new BigDecimal("250.00"),
                    "Setor A, assento 1"),
                new Item(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    new BigDecimal("250.00"),
                    "Setor A, assento 2")),
            Instant.now(),
            Instant.now().plusSeconds(600));
    publicar("inventario.reserva.v1", reserva, "ReservaCriada", evento);
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                jdbc.queryForObject(
                        "select count(*) from reserva_ref where reserva_id = ?",
                        Integer.class,
                        reserva)
                    == 1);
    return reserva;
  }

  private String publicar(String topico, UUID chave, String tipo, Object payload) throws Exception {
    var env =
        new EventEnvelope(
            UUID.randomUUID(),
            tipo,
            Instant.now(),
            chave.toString(),
            1,
            mapper.valueToTree(payload));
    String json = mapper.writeValueAsString(env);
    kafka.send(topico, chave.toString(), json).get();
    return json;
  }

  private UUID pedidoReservado(String clienteId) throws Exception {
    UUID reserva = reservaPublicada(clienteId);
    var r =
        mvc.perform(
                post("/pedidos")
                    .with(cliente(clienteId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reservaId\":\"" + reserva + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("RESERVADO"))
            .andReturn();
    return UUID.fromString(
        mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
  }

  private org.springframework.test.web.servlet.ResultActions pagar(
      String clienteId, String chave, UUID pedido) throws Exception {
    return mvc.perform(
        post("/pagamentos")
            .with(cliente(clienteId))
            .header("Idempotency-Key", chave)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pedidoId\":\"" + pedido + "\"}"));
  }

  private int contar(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  @Test
  void pedidoAbertoAntesDaReservaFicaCriadoEAvancaQuandoOEventoChega() throws Exception {
    UUID reserva = UUID.randomUUID();
    var r =
        mvc.perform(
                post("/pedidos")
                    .with(cliente("c-antes"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reservaId\":\"" + reserva + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("CRIADO"))
            .andReturn();
    String pedido = mapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

    var evento =
        new ReservaCriada(
            reserva,
            "c-antes",
            UUID.randomUUID(),
            "Show",
            List.of(
                new Item(
                    UUID.randomUUID(), UUID.randomUUID(), 2, new BigDecimal("100.00"), "Pista")),
            Instant.now(),
            Instant.now().plusSeconds(600));
    String json = publicar("inventario.reserva.v1", reserva, "ReservaCriada", evento);
    kafka.send("inventario.reserva.v1", reserva.toString(), json).get(); // duplicata

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                mvc.perform(get("/pedidos/" + pedido).with(cliente("c-antes")))
                    .andExpect(jsonPath("$.status").value("RESERVADO"))
                    .andExpect(jsonPath("$.total").value(200.00)));
    Thread.sleep(1000);
    assertThat(
            contar("select count(*) from pedido_item where pedido_id = ?", UUID.fromString(pedido)))
        .isEqualTo(1);
    assertThat(
            contar(
                "select count(*) from outbox_event where msg_key = ? and body like '%PedidoReservado%'",
                pedido))
        .isEqualTo(1);
  }

  @Test
  void mesmaIdempotencyKeyChamaOGatewayUmaVezEDevolveAMesmaResposta() throws Exception {
    UUID pedido = pedidoReservado("c-idem");

    String primeira =
        pagar("c-idem", "chave-1", pedido)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAGO"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String segunda =
        pagar("c-idem", "chave-1", pedido)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(segunda).isEqualTo(primeira);
    assertThat(Stack.CHAMADAS.get()).as("chamadas ao gateway").isEqualTo(1);
    assertThat(contar("select count(*) from pagamento where pedido_id = ?", pedido)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select status from pedido where id = ?", String.class, pedido))
        .isEqualTo("PAGO");
    assertThat(
            contar(
                "select count(*) from outbox_event where msg_key = ? and body like '%PedidoPago%'",
                pedido.toString()))
        .isEqualTo(1);
  }

  @Test
  void chaveReutilizadaComOutroPedidoDa422ESemChaveDa400() throws Exception {
    UUID pedidoA = pedidoReservado("c-422");
    UUID pedidoB = pedidoReservado("c-422");
    pagar("c-422", "chave-422", pedidoA).andExpect(status().isOk());

    pagar("c-422", "chave-422", pedidoB).andExpect(status().isUnprocessableEntity());
    mvc.perform(
            post("/pagamentos")
                .with(cliente("c-422"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pedidoId\":\"" + pedidoB + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void requisicoesConcorrentesComAMesmaChavePagamUmaSoVez() throws Exception {
    UUID pedido = pedidoReservado("c-conc");
    ExecutorService pool = Executors.newFixedThreadPool(20);
    CountDownLatch largada = new CountDownLatch(1);
    List<Future<Integer>> resultados = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      resultados.add(
          pool.submit(
              () -> {
                largada.await();
                return pagar("c-conc", "chave-conc", pedido).andReturn().getResponse().getStatus();
              }));
    }
    largada.countDown();
    List<Integer> codigos = new ArrayList<>();
    for (var f : resultados) {
      codigos.add(f.get());
    }
    pool.shutdown();

    assertThat(codigos).contains(200).allMatch(c -> c == 200 || c == 409);
    assertThat(Stack.CHAMADAS.get()).as("chamadas ao gateway").isEqualTo(1);
    assertThat(contar("select count(*) from pagamento where pedido_id = ?", pedido)).isEqualTo(1);
  }

  @Test
  void pagamentoRecusadoDevolvePedidoARESERVADOEPermiteNovaTentativa() throws Exception {
    UUID pedido = pedidoReservado("c-recusa");
    Stack.modo = "RECUSAR";

    pagar("c-recusa", "k1", pedido)
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.status").value("RECUSADO"));
    assertThat(jdbc.queryForObject("select status from pedido where id = ?", String.class, pedido))
        .isEqualTo("RESERVADO");
    assertThat(
            contar(
                "select count(*) from outbox_event where msg_key = ? and body like '%PagamentoRecusado%'",
                pedido.toString()))
        .isEqualTo(1);

    Stack.modo = "APROVAR";
    pagar("c-recusa", "k2", pedido)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAGO"));
    assertThat(contar("select count(*) from pagamento where pedido_id = ?", pedido)).isEqualTo(2);
  }

  @Test
  void gatewayForaDoArDa503AposRetentativasEAMesmaChaveFuncionaDepois() throws Exception {
    UUID pedido = pedidoReservado("c-503");
    Stack.modo = "ERRO";

    pagar("c-503", "k503", pedido).andExpect(status().isServiceUnavailable());
    assertThat(Stack.CHAMADAS.get()).as("3 tentativas com backoff").isEqualTo(3);
    assertThat(jdbc.queryForObject("select status from pedido where id = ?", String.class, pedido))
        .isEqualTo("RESERVADO");

    Stack.modo = "APROVAR";
    pagar("c-503", "k503", pedido)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAGO"));
  }

  @Test
  void reservaExpiradaOuCanceladaExpiraOPedidoEBloqueiaPagamento() throws Exception {
    UUID pedido = pedidoReservado("c-exp");
    UUID reserva =
        jdbc.queryForObject("select reserva_id from pedido where id = ?", UUID.class, pedido);
    String json =
        publicar(
            "inventario.reserva.v1",
            reserva,
            "ReservaExpirada",
            Map.of("reservaId", reserva, "motivo", "TEMPO_ESGOTADO"));
    kafka.send("inventario.reserva.v1", reserva.toString(), json).get(); // duplicata

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "select status from pedido where id = ?", String.class, pedido))
                    .isEqualTo("EXPIRADO"));
    Thread.sleep(1000);
    assertThat(
            contar(
                "select count(*) from outbox_event where msg_key = ? and body like '%PedidoExpirado%'",
                pedido.toString()))
        .isEqualTo(1);
    pagar("c-exp", "k-exp", pedido).andExpect(status().isConflict());
  }

  @Test
  void rotasExigemPapelClienteEPedidoENaoVazaEntreClientes() throws Exception {
    UUID pedido = pedidoReservado("c-dono");

    mvc.perform(post("/pagamentos").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/pedidos/" + pedido)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORGANIZADOR"))))
        .andExpect(status().isForbidden());
    mvc.perform(get("/pedidos/" + pedido).with(cliente("outro-cliente")))
        .andExpect(status().isNotFound());
    pagar("outro-cliente", "k-x", pedido).andExpect(status().isNotFound());
  }
}
