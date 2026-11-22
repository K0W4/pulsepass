package br.pucrs.pulsepass.iam.domain;

import br.pucrs.pulsepass.common.TransicaoInvalida;

/** Solicitação de organizador: PENDENTE é decidida uma vez; APROVADA e REJEITADA são finais. */
public enum SolicitacaoStatus {
  PENDENTE,
  APROVADA,
  REJEITADA;

  public SolicitacaoStatus para(SolicitacaoStatus proximo) {
    if (this == PENDENTE && (proximo == APROVADA || proximo == REJEITADA)) {
      return proximo;
    }
    throw new TransicaoInvalida(this, proximo);
  }
}
