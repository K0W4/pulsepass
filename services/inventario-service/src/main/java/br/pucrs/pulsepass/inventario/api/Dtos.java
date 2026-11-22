package br.pucrs.pulsepass.inventario.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Dtos {

  private Dtos() {}

  /** {@code publicar} nulo ou true publica na criação; false cria como rascunho (US10). */
  public record EventoRequest(
      String nome, String local, Instant dataHora, String imagemUrl, Boolean publicar) {

    public EventoRequest(String nome, String local, Instant dataHora, String imagemUrl) {
      this(nome, local, dataHora, imagemUrl, null);
    }
  }

  public record FilaRequest(boolean ativa) {}

  public record LoteRequest(
      String nome, BigDecimal preco, int quantidade, Instant inicioVenda, Instant fimVenda) {}

  public record SetorRequest(String nome, boolean marcado, UUID loteId, List<String> codigos) {}

  public record ItemLote(UUID loteId, int quantidade) {}

  public record ReservaRequest(UUID eventoId, List<UUID> assentoIds, List<ItemLote> itens) {}

  public record ReservaResponse(UUID reservaId, Instant expiraEm) {}
}
