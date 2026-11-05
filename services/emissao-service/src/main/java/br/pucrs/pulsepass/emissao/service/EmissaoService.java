package br.pucrs.pulsepass.emissao.service;

import br.pucrs.pulsepass.common.Outbox;
import br.pucrs.pulsepass.emissao.service.Eventos.IngressoEmitido;
import br.pucrs.pulsepass.emissao.service.Eventos.Item;
import br.pucrs.pulsepass.emissao.service.Eventos.PedidoPago;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Emite um ingresso por assento (ou por unidade de lote) quando o pedido é pago. */
@Service
public class EmissaoService {

  private final JdbcTemplate jdbc;
  private final Outbox outbox;

  public EmissaoService(JdbcTemplate jdbc, Outbox outbox) {
    this.jdbc = jdbc;
    this.outbox = outbox;
  }

  @Transactional
  public void emitir(PedidoPago pedido) {
    Instant agora = Instant.now();
    int ordem = 0;
    for (Item item : pedido.itens()) {
      for (int unidade = 0; unidade < item.quantidade(); unidade++) {
        UUID id = UUID.randomUUID();
        int inseridos =
            jdbc.update(
                """
                insert into ingresso(id, pedido_id, item_ordem, unidade, cliente_id, evento_id,
                                     evento_nome, assento_id, lote_id, descricao, emitido_em)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing
                """,
                id,
                pedido.pedidoId(),
                ordem,
                unidade,
                pedido.clienteId(),
                pedido.eventoId(),
                pedido.eventoNome(),
                item.assentoId(),
                item.loteId(),
                item.descricao(),
                Timestamp.from(agora));
        if (inseridos == 1) {
          outbox.add(
              Eventos.TOPICO_INGRESSO,
              pedido.pedidoId().toString(),
              "IngressoEmitido",
              new IngressoEmitido(
                  id,
                  pedido.pedidoId(),
                  pedido.clienteId(),
                  pedido.eventoId(),
                  item.assentoId(),
                  item.loteId(),
                  agora));
        }
      }
      ordem++;
    }
  }
}
