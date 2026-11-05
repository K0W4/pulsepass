package br.pucrs.pulsepass.inventario.service;

import br.pucrs.pulsepass.common.EventReader;
import br.pucrs.pulsepass.common.ProcessedEvents;
import br.pucrs.pulsepass.inventario.service.Eventos.PedidoRef;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Consome checkout.pedido.v1: confirma a venda e suspende a expiração durante o pagamento. */
@Component
public class PedidoEventsConsumer {

  private final EventReader reader;
  private final ProcessedEvents processados;
  private final ReservaService reservas;

  public PedidoEventsConsumer(
      EventReader reader, ProcessedEvents processados, ReservaService reservas) {
    this.reader = reader;
    this.processados = processados;
    this.reservas = reservas;
  }

  @KafkaListener(topics = "checkout.pedido.v1")
  @Transactional
  public void receber(String mensagem) {
    var envelope = reader.ler(mensagem);
    if (!processados.primeiraVez(envelope.eventId())) {
      return;
    }
    switch (envelope.eventType()) {
      case "PedidoPago" ->
          reservas.confirmarVenda(reader.payload(envelope, PedidoRef.class).reservaId());
      case "PedidoAguardandoPagamento" ->
          reservas.suspender(reader.payload(envelope, PedidoRef.class).reservaId());
      default -> {}
    }
  }
}
