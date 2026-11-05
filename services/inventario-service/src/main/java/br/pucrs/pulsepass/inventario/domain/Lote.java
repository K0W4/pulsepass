package br.pucrs.pulsepass.inventario.domain;

/** Capacidade de um lote sem assento marcado. */
public record Lote(int quantidade, int reservada, int vendida) {

  public int disponivel() {
    return quantidade - reservada - vendida;
  }

  public boolean comporta(int unidades) {
    return unidades > 0 && unidades <= disponivel();
  }
}
