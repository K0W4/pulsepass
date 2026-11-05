package br.pucrs.pulsepass.inventario.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Dtos {

  private Dtos() {}

  public record EventoRequest(String nome, String local, Instant dataHora, String imagemUrl) {}

  public record LoteRequest(
      String nome, BigDecimal preco, int quantidade, Instant inicioVenda, Instant fimVenda) {}

  public record SetorRequest(String nome, boolean marcado, UUID loteId, List<String> codigos) {}

  public record ItemLote(UUID loteId, int quantidade) {}

  public record ReservaRequest(UUID eventoId, List<UUID> assentoIds, List<ItemLote> itens) {}

  public record ReservaResponse(UUID reservaId, Instant expiraEm) {}
}
