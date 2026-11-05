package br.pucrs.pulsepass.checkout.domain;

import br.pucrs.pulsepass.common.TransicaoInvalida;

/** PENDENTE decide uma única vez: APROVADO ou RECUSADO. */
public enum PagamentoStatus {
  PENDENTE,
  APROVADO,
  RECUSADO;

  public PagamentoStatus para(PagamentoStatus proximo) {
    if (this != PENDENTE || proximo == PENDENTE) {
      throw new TransicaoInvalida(this, proximo);
    }
    return proximo;
  }
}
