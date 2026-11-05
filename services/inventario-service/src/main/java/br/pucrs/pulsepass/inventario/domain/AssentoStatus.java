package br.pucrs.pulsepass.inventario.domain;

import br.pucrs.pulsepass.common.TransicaoInvalida;

/** LIVRE -> RESERVADO -> VENDIDO, com devolução RESERVADO -> LIVRE; VENDIDO é final. */
public enum AssentoStatus {
  LIVRE,
  RESERVADO,
  VENDIDO;

  public AssentoStatus para(AssentoStatus proximo) {
    boolean permitido =
        switch (this) {
          case LIVRE -> proximo == RESERVADO;
          case RESERVADO -> proximo == LIVRE || proximo == VENDIDO;
          case VENDIDO -> false;
        };
    if (!permitido) {
      throw new TransicaoInvalida(this, proximo);
    }
    return proximo;
  }
}
