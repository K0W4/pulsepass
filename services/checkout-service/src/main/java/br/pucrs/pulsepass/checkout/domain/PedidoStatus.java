package br.pucrs.pulsepass.checkout.domain;

import br.pucrs.pulsepass.common.TransicaoInvalida;

/**
 * Máquina de estados do pedido. AGUARDANDO volta a RESERVADO quando o pagamento é recusado ou o
 * gateway falha (US23); PAGO e EXPIRADO são finais.
 */
public enum PedidoStatus {
  CRIADO,
  RESERVADO,
  AGUARDANDO,
  PAGO,
  EXPIRADO;

  public PedidoStatus para(PedidoStatus proximo) {
    boolean permitido =
        switch (this) {
          case CRIADO -> proximo == RESERVADO || proximo == EXPIRADO;
          case RESERVADO -> proximo == AGUARDANDO || proximo == EXPIRADO;
          case AGUARDANDO -> proximo == PAGO || proximo == RESERVADO || proximo == EXPIRADO;
          case PAGO, EXPIRADO -> false;
        };
    if (!permitido) {
      throw new TransicaoInvalida(this, proximo);
    }
    return proximo;
  }

  public boolean encerravel() {
    return this == CRIADO || this == RESERVADO || this == AGUARDANDO;
  }
}
