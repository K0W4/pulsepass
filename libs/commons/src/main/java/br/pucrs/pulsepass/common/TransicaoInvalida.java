package br.pucrs.pulsepass.common;

/** Lançada quando uma máquina de estados do domínio recebe uma transição proibida. */
public class TransicaoInvalida extends RuntimeException {

  public TransicaoInvalida(Enum<?> de, Enum<?> para) {
    super("Transição inválida: " + de + " -> " + para);
  }
}
