package br.pucrs.pulsepass.iam.service;

import java.time.Instant;
import java.util.List;

/** Porta para o provedor de identidade (Keycloak). Os testes usam um dublê. */
public interface Diretorio {

  /** Evento de acesso, já reduzido ao que a auditoria precisa; {@code chave} é única por evento. */
  record EventoAuditavel(
      String chave, String tipo, String usuarioId, String ip, String detalhes, Instant quando) {}

  /** Idempotente: atribuir um papel que o usuário já tem não é erro. */
  void atribuirPapel(String usuarioId, String papel);

  /** Eventos de acesso desde o instante dado (o provedor pode devolver alguns a mais). */
  List<EventoAuditavel> eventos(Instant desde);
}
