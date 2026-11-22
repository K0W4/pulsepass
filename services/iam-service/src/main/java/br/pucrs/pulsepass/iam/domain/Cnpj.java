package br.pucrs.pulsepass.iam.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * CNPJ numérico ou alfanumérico (formato adotado pela Receita em 2026): 12 caracteres [0-9A-Z] e 2
 * dígitos verificadores, calculados com módulo 11 sobre {@code caractere - '0'}.
 */
public final class Cnpj {

  private static final Pattern FORMATO = Pattern.compile("[0-9A-Z]{12}[0-9]{2}");

  private Cnpj() {}

  /** Tira pontos, barra, hífen e espaços e passa para maiúsculas; null continua null. */
  public static String normalizar(String bruto) {
    return bruto == null ? null : bruto.replaceAll("[.\\-/\\s]", "").toUpperCase(Locale.ROOT);
  }

  public static boolean valido(String bruto) {
    String c = normalizar(bruto);
    if (c == null || !FORMATO.matcher(c).matches() || c.chars().distinct().count() == 1) {
      return false;
    }
    return digito(c, 12) == c.charAt(12) - '0' && digito(c, 13) == c.charAt(13) - '0';
  }

  /** Pesos 2 a 9, da direita para a esquerda, repetindo; resto < 2 vira 0. */
  private static int digito(String c, int tamanho) {
    int soma = 0;
    int peso = tamanho - 7;
    for (int i = 0; i < tamanho; i++) {
      soma += (c.charAt(i) - '0') * peso;
      peso = peso == 2 ? 9 : peso - 1;
    }
    int resto = soma % 11;
    return resto < 2 ? 0 : 11 - resto;
  }
}
