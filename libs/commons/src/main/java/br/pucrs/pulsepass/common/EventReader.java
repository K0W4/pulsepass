package br.pucrs.pulsepass.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Lê o envelope JSON recebido do Kafka. */
public class EventReader {

  private final ObjectMapper mapper;

  public EventReader(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public EventEnvelope ler(String json) {
    try {
      return mapper.readValue(json, EventEnvelope.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Envelope inválido", e);
    }
  }

  public <T> T payload(EventEnvelope envelope, Class<T> tipo) {
    try {
      return mapper.treeToValue(envelope.payload(), tipo);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Payload inválido: " + envelope.eventType(), e);
    }
  }
}
