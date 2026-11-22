package br.pucrs.pulsepass.realtime.kafka;

import br.pucrs.pulsepass.realtime.domain.Roteador;
import br.pucrs.pulsepass.realtime.ws.Canais;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Cada réplica lê todos os eventos (grupo único por instância, a partir do fim do tópico) e entrega
 * aos clientes conectados a ela. Não há o que reprocessar: quem conecta depois consulta a API.
 */
@Component
public class EventosListener {

  private final Canais canais;

  public EventosListener(Canais canais) {
    this.canais = canais;
  }

  @KafkaListener(
      topics = {Roteador.TOPICO_ASSENTO, Roteador.TOPICO_PEDIDO, Roteador.TOPICO_INGRESSO},
      groupId = "#{T(java.util.UUID).randomUUID().toString()}",
      properties = {"auto.offset.reset=latest", "enable.auto.commit=true"})
  void receber(ConsumerRecord<String, String> registro) {
    Roteador.rotear(registro.topic(), registro.value())
        .ifPresent(e -> canais.entregar(e.canal(), e.mensagem()));
  }
}
