package br.pucrs.pulsepass.emissao.domain;

import br.pucrs.pulsepass.common.TransicaoInvalida;

/** EMITIDO é o único estado que evolui; UTILIZADO e CANCELADO são finais. */
public enum IngressoStatus {
  EMITIDO,
  UTILIZADO,
  CANCELADO;

  public IngressoStatus para(IngressoStatus proximo) {
    if (this != EMITIDO || proximo == EMITIDO) {
      throw new TransicaoInvalida(this, proximo);
    }
    return proximo;
  }
}
