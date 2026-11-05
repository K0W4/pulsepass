package br.pucrs.pulsepass.checkout.domain;

import java.math.BigDecimal;
import java.util.List;

/** Cálculo do total do pedido a partir dos itens da reserva. */
public final class Total {

  private Total() {}

  public record Linha(int quantidade, BigDecimal precoUnitario) {}

  public static BigDecimal de(List<Linha> linhas) {
    return linhas.stream()
        .map(l -> l.precoUnitario().multiply(BigDecimal.valueOf(l.quantidade())))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, java.math.RoundingMode.HALF_UP);
  }
}
