package br.pucrs.pulsepass.emissao.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Payloads consumidos e publicados pela Emissão (catálogo da seção 4.3 do Pacote 2). */
public final class Eventos {

  private Eventos() {}

  public static final String TOPICO_PEDIDO = "checkout.pedido.v1";
  public static final String TOPICO_INGRESSO = "emissao.ingresso.v1";

  public record Item(
      UUID assentoId, UUID loteId, int quantidade, BigDecimal precoUnitario, String descricao) {}

  public record PedidoPago(
      UUID pedidoId,
      UUID reservaId,
      String clienteId,
      UUID eventoId,
      String eventoNome,
      List<Item> itens,
      BigDecimal total,
      Instant pagoEm) {}

  public record IngressoEmitido(
      UUID ingressoId,
      UUID pedidoId,
      String clienteId,
      UUID eventoId,
      UUID assentoId,
      UUID loteId,
      Instant emitidoEm) {}

  public record IngressoUtilizado(UUID ingressoId, String operadorId, Instant lidoEm) {}
}
