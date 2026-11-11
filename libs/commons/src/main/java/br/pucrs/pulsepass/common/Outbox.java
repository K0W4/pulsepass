package br.pucrs.pulsepass.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** Grava o evento na tabela outbox_event, na mesma transação do estado (sem escrita dupla). */
public class Outbox {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final ObjectProvider<Tracer> tracer;

  public Outbox(JdbcTemplate jdbc, ObjectMapper mapper, ObjectProvider<Tracer> tracer) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.tracer = tracer;
  }

  /** Contexto do trace atual, para o relay reaplicar ao publicar (RNF16). */
  private String traceparent() {
    Tracer t = tracer.getIfAvailable();
    Span span = t == null ? null : t.currentSpan();
    if (span == null) {
      return null;
    }
    var ctx = span.context();
    return TraceParent.formatar(ctx.traceId(), ctx.spanId(), Boolean.TRUE.equals(ctx.sampled()));
  }

  public void add(String topic, String aggregateId, String type, Object payload) {
    var envelope =
        new EventEnvelope(
            UUID.randomUUID(), type, Instant.now(), aggregateId, 1, mapper.valueToTree(payload));
    try {
      jdbc.update(
          "insert into outbox_event(id, topic, msg_key, body, traceparent) values (?, ?, ?, ?, ?)",
          envelope.eventId(),
          topic,
          aggregateId,
          mapper.writeValueAsString(envelope),
          traceparent());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Evento não serializável: " + type, e);
    }
  }
}
