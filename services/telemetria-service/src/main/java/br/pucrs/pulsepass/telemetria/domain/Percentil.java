package br.pucrs.pulsepass.telemetria.domain;

import java.util.Arrays;
import java.util.OptionalDouble;

/** Percentil com interpolação linear (o mesmo critério do percentile_cont do SQL). */
public final class Percentil {

  /** Meta do RNF08: ingresso disponível em até 30 s no p95. */
  public static final double META_EMISSAO_SEGUNDOS = 30.0;

  private Percentil() {}

  /** {@code p} entre 0 e 1; vazio se não houver amostras. */
  public static OptionalDouble de(double[] amostras, double p) {
    if (p < 0 || p > 1) {
      throw new IllegalArgumentException("p deve estar entre 0 e 1");
    }
    if (amostras.length == 0) {
      return OptionalDouble.empty();
    }
    double[] ordenadas = amostras.clone();
    Arrays.sort(ordenadas);
    double posicao = p * (ordenadas.length - 1);
    int baixo = (int) Math.floor(posicao);
    int alto = (int) Math.ceil(posicao);
    double fracao = posicao - baixo;
    return OptionalDouble.of(ordenadas[baixo] + (ordenadas[alto] - ordenadas[baixo]) * fracao);
  }

  /** Sem amostras não há como afirmar que a meta foi atendida. */
  public static boolean atendeMeta(OptionalDouble p95) {
    return p95.isPresent() && p95.getAsDouble() <= META_EMISSAO_SEGUNDOS;
  }
}
