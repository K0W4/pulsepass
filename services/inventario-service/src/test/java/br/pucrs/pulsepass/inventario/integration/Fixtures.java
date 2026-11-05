package br.pucrs.pulsepass.inventario.integration;

import br.pucrs.pulsepass.inventario.api.Dtos.EventoRequest;
import br.pucrs.pulsepass.inventario.api.Dtos.LoteRequest;
import br.pucrs.pulsepass.inventario.api.Dtos.SetorRequest;
import br.pucrs.pulsepass.inventario.service.EventoService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Cria um evento com um lote de assentos marcados (e opcionalmente um lote de pista). */
record Fixtures(UUID eventoId, UUID loteAssentos, UUID loteAssento, UUID assentoId) {

  static final String ORGANIZADOR = "organizador-1";

  static Fixtures criar(EventoService eventos, JdbcTemplate jdbc, String codigoAssento) {
    UUID evento =
        eventos.criar(
            ORGANIZADOR,
            new EventoRequest(
                "Show " + UUID.randomUUID(),
                "Arena",
                Instant.now().plus(30, ChronoUnit.DAYS),
                null));
    UUID lote =
        eventos.criarLote(
            evento,
            ORGANIZADOR,
            new LoteRequest(
                "Pista premium",
                new BigDecimal("250.00"),
                10,
                Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(20, ChronoUnit.DAYS)));
    eventos.criarSetor(
        evento, ORGANIZADOR, new SetorRequest("Setor A", true, lote, List.of(codigoAssento)));
    UUID assento =
        jdbc.queryForObject(
            "select a.id from assento a join setor s on s.id = a.setor_id where s.evento_id = ?",
            UUID.class,
            evento);
    return new Fixtures(evento, lote, lote, assento);
  }

  static UUID lotePista(EventoService eventos, UUID evento, int quantidade) {
    return eventos.criarLote(
        evento,
        ORGANIZADOR,
        new LoteRequest(
            "Pista " + UUID.randomUUID(),
            new BigDecimal("100.00"),
            quantidade,
            Instant.now().minus(1, ChronoUnit.DAYS),
            Instant.now().plus(20, ChronoUnit.DAYS)));
  }
}
