package br.pucrs.pulsepass.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** Envelope de todo evento publicado no Kafka (seção 4.1 do Pacote 2). */
public record EventEnvelope(
    UUID eventId,
    String eventType,
    Instant occurredAt,
    String aggregateId,
    int version,
    JsonNode payload) {}
