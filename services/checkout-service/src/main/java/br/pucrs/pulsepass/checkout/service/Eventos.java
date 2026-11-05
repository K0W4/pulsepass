package br.pucrs.pulsepass.checkout.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Payloads publicados e consumidos pelo Checkout (catálogo da seção 4.3 do Pacote 2). */
public final class Eventos {

  private Eventos() {}

  public static final String TOPICO_PEDIDO = "checkout.pedido.v1";
  public static final String TOPICO_RESERVA = "inventario.reserva.v1";

  public record Item(
      UUID assentoId, UUID loteId, int quantidade, BigDecimal precoUnitario, String descricao) {}

  /** Consumido de inventario.reserva.v1. */
  public record ReservaCriada(
      UUID reservaId,
      String clienteId,
      UUID eventoId,
      String eventoNome,
      List<Item> itens,
      Instant criadaEm,
      Instant expiraEm) {}

  /** Consumido de inventario.reserva.v1 (ReservaExpirada e ReservaCancelada). */
  public record ReservaEncerrada(UUID reservaId, String motivo) {}

  public record PedidoMudou(UUID pedidoId, UUID reservaId, BigDecimal total, Instant expiraEm) {}

  public record PedidoPago(
      UUID pedidoId,
      UUID reservaId,
      String clienteId,
      UUID eventoId,
      String eventoNome,
      List<Item> itens,
      BigDecimal total,
      Instant pagoEm) {}

  public record PagamentoRecusado(
      UUID pedidoId, UUID reservaId, String motivo, Instant ocorridoEm) {}

  public record PedidoExpirado(UUID pedidoId, UUID reservaId, String motivo, Instant ocorridoEm) {}
}
