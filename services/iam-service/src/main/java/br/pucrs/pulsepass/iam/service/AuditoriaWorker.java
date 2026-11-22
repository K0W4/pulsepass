package br.pucrs.pulsepass.iam.service;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Copia os eventos de acesso do Keycloak para a auditoria (US06). Se o Keycloak estiver fora, tenta
 * de novo na rodada seguinte; a chave única da auditoria torna a repetição inofensiva.
 */
@Component
public class AuditoriaWorker {

  private static final Logger log = LoggerFactory.getLogger(AuditoriaWorker.class);

  /** Os eventos do Keycloak ficam 7 dias (ver o realm): é até onde dá para recuperar. */
  private static final Duration RETROATIVO = Duration.ofDays(7);

  private final Diretorio diretorio;
  private final AuditoriaService auditoria;

  public AuditoriaWorker(Diretorio diretorio, AuditoriaService auditoria) {
    this.diretorio = diretorio;
    this.auditoria = auditoria;
  }

  @Scheduled(fixedDelayString = "${iam.auditoria.poll-ms:10000}")
  public void sincronizar() {
    try {
      Instant ultimo = auditoria.ultimoDoKeycloak();
      Instant desde = ultimo == null ? Instant.now().minus(RETROATIVO) : ultimo;
      int novos = 0;
      for (var e : diretorio.eventos(desde)) {
        if (auditoria.registrar(AuditoriaService.ORIGEM_KEYCLOAK, e)) {
          novos++;
        }
      }
      if (novos > 0) {
        log.info("Auditoria: {} eventos novos do Keycloak", novos);
      }
    } catch (RuntimeException e) {
      log.warn("Auditoria não sincronizou: {}", e.toString());
    }
  }
}
