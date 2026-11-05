package br.pucrs.pulsepass.inventario.service;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Libera reservas vencidas. SKIP LOCKED permite várias réplicas sem conflito (RNF09). */
@Component
public class ExpiracaoWorker {

  private final JdbcTemplate jdbc;
  private final ReservaService reservas;

  public ExpiracaoWorker(JdbcTemplate jdbc, ReservaService reservas) {
    this.jdbc = jdbc;
    this.reservas = reservas;
  }

  @Scheduled(fixedDelayString = "${pulsepass.expiracao.poll-ms:2000}")
  @Transactional
  public void expirarVencidas() {
    List<UUID> vencidas =
        jdbc.queryForList(
            """
            select id from reserva
            where status = 'ATIVA' and expira_em <= now()
              and (suspensa_ate is null or suspensa_ate <= now())
            order by expira_em limit 100
            for update skip locked
            """,
            UUID.class);
    vencidas.forEach(reservas::expirar);
  }
}
