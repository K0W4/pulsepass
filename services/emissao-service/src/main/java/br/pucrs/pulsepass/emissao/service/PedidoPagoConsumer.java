package br.pucrs.pulsepass.emissao.service;

import br.pucrs.pulsepass.common.EventReader;
import br.pucrs.pulsepass.common.ProcessedEvents;
import br.pucrs.pulsepass.emissao.service.Eventos.PedidoPago;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Consome checkout.pedido.v1; ignora tudo que não for PedidoPago e deduplica por eventId. */
@Component
public class PedidoPagoConsumer {

  private final EventReader reader;
  private final ProcessedEvents processados;
  private final EmissaoService emissao;

  public PedidoPagoConsumer(
      EventReader reader, ProcessedEvents processados, EmissaoService emissao) {
    this.reader = reader;
    this.processados = processados;
    this.emissao = emissao;
  }

  @KafkaListener(topics = Eventos.TOPICO_PEDIDO)
  @Transactional
  public void receber(String mensagem) {
    var envelope = reader.ler(mensagem);
    if (!"PedidoPago".equals(envelope.eventType())
        || !processados.primeiraVez(envelope.eventId())) {
      return;
    }
    emissao.emitir(reader.payload(envelope, PedidoPago.class));
  }
}
