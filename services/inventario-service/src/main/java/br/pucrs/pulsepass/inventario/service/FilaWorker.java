package br.pucrs.pulsepass.inventario.service;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A cada segundo admite uma fatia da fila de cada evento com a fila ligada (US18). */
@Component
public class FilaWorker {

  private static final Logger log = LoggerFactory.getLogger(FilaWorker.class);

  private final FilaService fila;
  private final int porSegundo;

  public FilaWorker(
      FilaService fila, @Value("${pulsepass.fila.admissoes-por-segundo:20}") int porSegundo) {
    this.fila = fila;
    this.porSegundo = porSegundo;
  }

  @Scheduled(fixedDelayString = "${pulsepass.fila.poll-ms:1000}")
  public void admitir() {
    try {
      for (var evento : fila.eventosComFila()) {
        // 90% do intervalo: duas réplicas não admitem na mesma rodada, mas nenhuma rodada se perde
        if (fila.minhaVez(evento, Duration.ofMillis(900))) {
          int n = fila.admitir(evento, porSegundo);
          if (n > 0) {
            log.info("Fila do evento {}: {} admitidos", evento, n);
          }
        }
      }
    } catch (RuntimeException e) {
      log.warn("Falha ao admitir a fila: {}", e.toString());
    }
  }
}
