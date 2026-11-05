package br.pucrs.pulsepass.checkout.service;

import br.pucrs.pulsepass.checkout.service.Eventos.ReservaCriada;
import br.pucrs.pulsepass.checkout.service.Eventos.ReservaEncerrada;
import br.pucrs.pulsepass.common.EventReader;
import br.pucrs.pulsepass.common.ProcessedEvents;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Consome inventario.reserva.v1, com deduplicação por eventId (RNF05). */
@Component
public class ReservaEventsConsumer {

  private final EventReader reader;
  private final ProcessedEvents processados;
  private final PedidoService pedidos;

  public ReservaEventsConsumer(
      EventReader reader, ProcessedEvents processados, PedidoService pedidos) {
    this.reader = reader;
    this.processados = processados;
    this.pedidos = pedidos;
  }

  @KafkaListener(topics = Eventos.TOPICO_RESERVA)
  @Transactional
  public void receber(String mensagem) {
    var envelope = reader.ler(mensagem);
    if (!processados.primeiraVez(envelope.eventId())) {
      return;
    }
    switch (envelope.eventType()) {
      case "ReservaCriada" ->
          pedidos.aplicarReservaCriada(reader.payload(envelope, ReservaCriada.class));
      case "ReservaExpirada" ->
          pedidos.aplicarReservaEncerrada(
              reader.payload(envelope, ReservaEncerrada.class).reservaId(), "EXPIRADO", "EXPIRADA");
      case "ReservaCancelada" ->
          pedidos.aplicarReservaEncerrada(
              reader.payload(envelope, ReservaEncerrada.class).reservaId(),
              "CANCELADO_PELO_CLIENTE",
              "CANCELADA");
      default -> {}
    }
  }
}
