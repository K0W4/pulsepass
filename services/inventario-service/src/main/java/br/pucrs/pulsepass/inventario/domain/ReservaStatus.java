package br.pucrs.pulsepass.inventario.domain;

import br.pucrs.pulsepass.common.TransicaoInvalida;

/** Máquina de estados da reserva: só ATIVA evolui, e nunca volta a ATIVA. */
public enum ReservaStatus {
  ATIVA,
  CONFIRMADA,
  EXPIRADA,
  CANCELADA;

  public ReservaStatus para(ReservaStatus proximo) {
    if (this != ATIVA || proximo == ATIVA) {
      throw new TransicaoInvalida(this, proximo);
    }
    return proximo;
  }
}
