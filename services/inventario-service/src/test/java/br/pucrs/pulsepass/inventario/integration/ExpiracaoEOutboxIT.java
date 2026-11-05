package br.pucrs.pulsepass.inventario.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.pucrs.pulsepass.inventario.service.EventoService;
import br.pucrs.pulsepass.inventario.service.ReservaService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** RNF02 (expiração) e RNF04 (Outbox sobrevive à queda do Kafka). */
@SpringBootTest
@TestPropertySource(properties = "pulsepass.reserva.ttl=PT2S")
class ExpiracaoEOutboxIT extends ContainersBase {

  @Autowired ReservaService reservas;
  @Autowired EventoService eventos;
  @Autowired JdbcTemplate jdbc;

  @Test
  void reservaNaoPagaExpiraELiberaOAssento() {
    var f = Fixtures.criar(eventos, jdbc, "D1");
    UUID reserva =
        reservas.reservar("cliente-1", f.eventoId(), List.of(f.assentoId()), null).reservaId();

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> {
              assertThat(statusReserva(reserva)).isEqualTo("EXPIRADA");
              assertThat(statusAssento(f.assentoId())).isEqualTo("LIVRE");
            });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where msg_key = ? and body like '%ReservaExpirada%'",
                Integer.class, reserva.toString()))
        .isEqualTo(1);
  }

  @Test
  void expiracaoFicaSuspensaEnquantoOPagamentoEstaEmCurso() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "D2");
    UUID reserva =
        reservas.reservar("cliente-2", f.eventoId(), List.of(f.assentoId()), null).reservaId();

    reservas.suspender(reserva);
    Thread.sleep(4000); // passa do TTL de 2 s, mas a suspensão dura 30 s

    assertThat(statusReserva(reserva)).isEqualTo("ATIVA");
  }

  @Test
  void outboxSobreviveAQuedaDoKafka() throws Exception {
    var f = Fixtures.criar(eventos, jdbc, "D3");
    var docker = KAFKA.getDockerClient();

    docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
    UUID reserva;
    try {
      reserva =
          reservas.reservar("cliente-3", f.eventoId(), List.of(f.assentoId()), null).reservaId();
      Thread.sleep(7000);
      assertThat(pendentes(reserva)).as("Kafka fora: evento segue pendente no Outbox").isPositive();
    } finally {
      docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(pendentes(reserva)).as("Kafka voltou: Outbox publica").isZero());
    var lidos = consumir("inventario.reserva.v1", Duration.ofSeconds(5));
    assertThat(lidos)
        .anyMatch(r -> r.key().equals(reserva.toString()) && r.value().contains("ReservaCriada"));
  }

  private String statusReserva(UUID id) {
    return jdbc.queryForObject("select status from reserva where id = ?", String.class, id);
  }

  private String statusAssento(UUID id) {
    return jdbc.queryForObject("select status from assento where id = ?", String.class, id);
  }

  private int pendentes(UUID reserva) {
    return jdbc.queryForObject(
        "select count(*) from outbox_event where msg_key = ? and published_at is null",
        Integer.class,
        reserva.toString());
  }
}
