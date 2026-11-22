package br.pucrs.pulsepass.telemetria.service;

import br.pucrs.pulsepass.common.EventReader;
import br.pucrs.pulsepass.common.ProcessedEvents;
import br.pucrs.pulsepass.telemetria.domain.Agregador;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consome os tópicos de domínio e a navegação (US32). Deduplica por eventId e grava a contagem na
 * mesma transação (RNF05): reentrega do Kafka não conta duas vezes.
 */
@Component
public class EventosConsumer {

  private final EventReader reader;
  private final ProcessedEvents processados;
  private final Metricas metricas;

  public EventosConsumer(EventReader reader, ProcessedEvents processados, Metricas metricas) {
    this.reader = reader;
    this.processados = processados;
    this.metricas = metricas;
  }

  @KafkaListener(
      topics = {
        Agregador.TOPICO_RESERVA,
        Agregador.TOPICO_PEDIDO,
        Agregador.TOPICO_INGRESSO,
        Agregador.TOPICO_IAM,
        Agregador.TOPICO_NAVEGACAO
      })
  @Transactional
  public void receber(ConsumerRecord<String, String> registro) {
    var envelope = reader.ler(registro.value());
    if (!processados.primeiraVez(envelope.eventId())) {
      return;
    }
    String topico = registro.topic();
    String tipo = envelope.eventType();
    JsonNode p = envelope.payload();
    Instant quando = envelope.occurredAt() == null ? Instant.now() : envelope.occurredAt();

    if (Agregador.TOPICO_NAVEGACAO.equals(topico)) {
      String pagina = p == null ? null : texto(p, "pagina");
      metricas.contar("pagina." + Agregador.pagina(pagina), quando);
    }
    Agregador.metrica(topico, tipo).ifPresent(nome -> metricas.contar(nome, quando));

    if (p != null && Agregador.TOPICO_PEDIDO.equals(topico) && "PedidoPago".equals(tipo)) {
      UUID pedido = uuid(p, "pedidoId");
      if (pedido != null) {
        metricas.registrarPago(pedido, instante(p.get("pagoEm"), quando));
      }
    }
    if (p != null && Agregador.TOPICO_INGRESSO.equals(topico) && "IngressoEmitido".equals(tipo)) {
      UUID pedido = uuid(p, "pedidoId");
      if (pedido != null) {
        metricas.registrarEmitido(pedido, instante(p.get("emitidoEm"), quando));
      }
    }
  }

  private static String texto(JsonNode no, String campo) {
    JsonNode v = no.get(campo);
    return v == null || v.isNull() ? null : v.asText();
  }

  private static UUID uuid(JsonNode no, String campo) {
    try {
      String t = texto(no, campo);
      return t == null ? null : UUID.fromString(t);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** O Jackson grava Instant como texto ISO-8601; aceita também segundos decimais. */
  private static Instant instante(JsonNode no, Instant padrao) {
    if (no == null || no.isNull()) {
      return padrao;
    }
    if (no.isNumber()) {
      return Instant.ofEpochSecond(0).plusNanos((long) (no.asDouble() * 1_000_000_000L));
    }
    try {
      return Instant.parse(no.asText());
    } catch (java.time.format.DateTimeParseException e) {
      return padrao;
    }
  }
}
