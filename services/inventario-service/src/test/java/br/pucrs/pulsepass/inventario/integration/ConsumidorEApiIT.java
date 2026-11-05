package br.pucrs.pulsepass.inventario.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.pucrs.pulsepass.common.EventEnvelope;
import br.pucrs.pulsepass.inventario.service.EventoService;
import br.pucrs.pulsepass.inventario.service.ReservaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/** Consumo idempotente de PedidoPago (RNF05) e RBAC das rotas (RNF13). */
@SpringBootTest
@AutoConfigureMockMvc
class ConsumidorEApiIT extends ContainersBase {

  @Autowired ReservaService reservas;
  @Autowired EventoService eventos;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;

  @Test
  void pedidoPagoConfirmaAVendaEReprocessarNaoDuplicaEfeitos() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "E1");
    UUID pista = Fixtures.lotePista(eventos, f.eventoId(), 5);
    UUID reserva =
        reservas
            .reservar(
                "cliente-1",
                f.eventoId(),
                List.of(f.assentoId()),
                List.of(new br.pucrs.pulsepass.inventario.api.Dtos.ItemLote(pista, 2)))
            .reservaId();
    String mensagem =
        envelope("PedidoPago", Map.of("pedidoId", UUID.randomUUID(), "reservaId", reserva));

    kafka.send("checkout.pedido.v1", reserva.toString(), mensagem).get();
    kafka.send("checkout.pedido.v1", reserva.toString(), mensagem).get(); // mesma mensagem de novo

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(
                      jdbc.queryForObject(
                          "select status from reserva where id = ?", String.class, reserva))
                  .isEqualTo("CONFIRMADA");
              assertThat(
                      jdbc.queryForObject(
                          "select status from assento where id = ?", String.class, f.assentoId()))
                  .isEqualTo("VENDIDO");
            });
    Thread.sleep(1500); // dá tempo de a duplicata ser consumida
    assertThat(
            jdbc.queryForObject("select qtd_vendida from lote where id = ?", Integer.class, pista))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select qtd_reservada from lote where id = ?", Integer.class, pista))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where msg_key = ? and body like '%AssentoVendido%'",
                Integer.class, f.eventoId().toString()))
        .as("um único AssentoVendido para o assento")
        .isEqualTo(1);
  }

  @Test
  void clienteNaoCriaEventoEAnonimoNaoReserva() throws Exception {
    String corpo = "{\"nome\":\"X\",\"local\":\"Y\",\"dataHora\":\"2027-01-01T20:00:00Z\"}";

    mvc.perform(
            post("/eventos")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENTE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/eventos")
                .with(
                    jwt()
                        .jwt(j -> j.subject("org-9"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ORGANIZADOR")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isCreated());
    mvc.perform(post("/reservas").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/eventos")).andExpect(status().isOk());
  }

  @Test
  void reservaDoCatalogoRetorna201Depois409() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "E2");
    String corpo =
        "{\"eventoId\":\"%s\",\"assentoIds\":[\"%s\"]}".formatted(f.eventoId(), f.assentoId());
    var cliente =
        jwt()
            .jwt(j -> j.subject("cliente-api"))
            .authorities(new SimpleGrantedAuthority("ROLE_CLIENTE"));

    mvc.perform(
            post("/reservas").with(cliente).contentType(MediaType.APPLICATION_JSON).content(corpo))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/reservas").with(cliente).contentType(MediaType.APPLICATION_JSON).content(corpo))
        .andExpect(status().isConflict());
  }

  @Test
  void clienteCancelaAReservaEOutroClienteNaoConsegue() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "F1");
    UUID reserva =
        reservas.reservar("dono", f.eventoId(), List.of(f.assentoId()), null).reservaId();
    var dono =
        jwt().jwt(j -> j.subject("dono")).authorities(new SimpleGrantedAuthority("ROLE_CLIENTE"));
    var outro =
        jwt().jwt(j -> j.subject("outro")).authorities(new SimpleGrantedAuthority("ROLE_CLIENTE"));

    mvc.perform(delete("/reservas/" + reserva).with(outro)).andExpect(status().isNotFound());
    mvc.perform(delete("/reservas/" + reserva).with(dono)).andExpect(status().isNoContent());
    mvc.perform(delete("/reservas/" + reserva).with(dono)).andExpect(status().isConflict());

    assertThat(
            jdbc.queryForObject(
                "select status from assento where id = ?", String.class, f.assentoId()))
        .isEqualTo("LIVRE");
    assertThat(
            jdbc.queryForObject("select status from reserva where id = ?", String.class, reserva))
        .isEqualTo("CANCELADA");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where msg_key = ? and body like '%ReservaCancelada%'",
                Integer.class, reserva.toString()))
        .isEqualTo(1);
  }

  @Test
  void painelDeVendasMostraOcupacaoPorLoteSoParaODonoDoEvento() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "G1");
    reservas.reservar("c-vendas", f.eventoId(), List.of(f.assentoId()), null);
    var dono =
        jwt()
            .jwt(j -> j.subject(Fixtures.ORGANIZADOR))
            .authorities(new SimpleGrantedAuthority("ROLE_ORGANIZADOR"));
    var outroOrg =
        jwt()
            .jwt(j -> j.subject("org-intruso"))
            .authorities(new SimpleGrantedAuthority("ROLE_ORGANIZADOR"));
    var cliente =
        jwt()
            .jwt(j -> j.subject("c-vendas"))
            .authorities(new SimpleGrantedAuthority("ROLE_CLIENTE"));

    mvc.perform(get("/eventos/" + f.eventoId() + "/vendas").with(dono))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].quantidade").value(10))
        .andExpect(jsonPath("$[0].qtd_vendida").value(0));
    mvc.perform(get("/eventos/" + f.eventoId() + "/vendas").with(outroOrg))
        .andExpect(status().isNotFound());
    mvc.perform(get("/eventos/" + f.eventoId() + "/vendas").with(cliente))
        .andExpect(status().isForbidden());
  }

  private String envelope(String tipo, Map<String, Object> payload) throws Exception {
    var env =
        new EventEnvelope(
            UUID.randomUUID(), tipo, Instant.now(), "agregado", 1, mapper.valueToTree(payload));
    return mapper.writeValueAsString(env);
  }
}
