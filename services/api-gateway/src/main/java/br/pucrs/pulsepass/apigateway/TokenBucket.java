package br.pucrs.pulsepass.apigateway;

/**
 * Balde de fichas: reabastece {@code porSegundo} fichas por segundo até o máximo {@code rajada}.
 */
public final class TokenBucket {

  private final double porSegundo;
  private final double rajada;
  private double fichas;
  private long ultimoNs;

  public TokenBucket(double porSegundo, double rajada, long agoraNs) {
    this.porSegundo = porSegundo;
    this.rajada = rajada;
    this.fichas = rajada;
    this.ultimoNs = agoraNs;
  }

  /** Devolve 0 se consumiu uma ficha; senão, os milissegundos até haver uma ficha. */
  public synchronized long tentar(long agoraNs) {
    fichas = Math.min(rajada, fichas + (agoraNs - ultimoNs) / 1e9 * porSegundo);
    ultimoNs = agoraNs;
    if (fichas >= 1) {
      fichas -= 1;
      return 0;
    }
    return (long) Math.ceil((1 - fichas) / porSegundo * 1000);
  }

  /** Balde cheio e parado há mais de um minuto: pode ser descartado. */
  public synchronized boolean ocioso(long agoraNs) {
    return agoraNs - ultimoNs > 60_000_000_000L;
  }
}
