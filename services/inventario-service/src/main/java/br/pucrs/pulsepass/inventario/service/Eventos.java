package br.pucrs.pulsepass.inventario.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Payloads publicados pelo Inventário (catálogo de eventos da seção 4.3 do Pacote 2). */
public final class Eventos {

  private Eventos() {}

  public static final String TOPICO_RESERVA = "inventario.reserva.v1";
  public static final String TOPICO_ASSENTO = "inventario.assento.v1";

  public record Item(
      UUID assentoId, UUID loteId, int quantidade, BigDecimal precoUnitario, String descricao) {}

  public record ReservaCriada(
      UUID reservaId,
      String clienteId,
      UUID eventoId,
      String eventoNome,
      List<Item> itens,
      Instant criadaEm,
      Instant expiraEm) {}

  public record ReservaEncerrada(UUID reservaId, String motivo, Instant ocorridaEm) {}

  public record AssentoMudou(UUID eventoId, UUID assentoId, String status) {}

  /** Campos de checkout.pedido.v1 usados pelo Inventário. */
  public record PedidoRef(UUID pedidoId, UUID reservaId) {}
}
