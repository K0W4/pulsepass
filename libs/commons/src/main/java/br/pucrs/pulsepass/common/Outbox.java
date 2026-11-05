package br.pucrs.pulsepass.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Grava o evento na tabela outbox_event, na mesma transação do estado (sem escrita dupla). */
public class Outbox {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public Outbox(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public void add(String topic, String aggregateId, String type, Object payload) {
    var envelope =
        new EventEnvelope(
            UUID.randomUUID(), type, Instant.now(), aggregateId, 1, mapper.valueToTree(payload));
    try {
      jdbc.update(
          "insert into outbox_event(id, topic, msg_key, body) values (?, ?, ?, ?)",
          envelope.eventId(),
          topic,
          aggregateId,
          mapper.writeValueAsString(envelope));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Evento não serializável: " + type, e);
    }
  }
}
