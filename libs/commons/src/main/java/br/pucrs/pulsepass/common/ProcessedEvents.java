package br.pucrs.pulsepass.common;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Deduplicação do consumo: a chave primária de evento_processado decide quem chegou primeiro. */
public class ProcessedEvents {

  private final JdbcTemplate jdbc;

  public ProcessedEvents(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Deve ser chamado dentro da transação do efeito; false significa evento já tratado. */
  public boolean primeiraVez(UUID eventId) {
    return jdbc.update(
            "insert into evento_processado(event_id) values (?) on conflict do nothing", eventId)
        == 1;
  }
}
