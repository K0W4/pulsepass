package br.pucrs.pulsepass.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publica as linhas pendentes do Outbox no Kafka (at-least-once). Várias réplicas convivem porque
 * as linhas são travadas com FOR UPDATE SKIP LOCKED.
 */
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final JdbcTemplate jdbc;
  private final KafkaTemplate<String, String> kafka;
  private final TransactionTemplate tx;

  public OutboxRelay(
      JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.kafka = kafka;
    this.tx = tx;
  }

  @Scheduled(fixedDelayString = "${pulsepass.outbox.poll-ms:500}")
  public void publicar() {
    try {
      tx.executeWithoutResult(status -> publicarLote());
    } catch (RuntimeException e) {
      // Kafka fora do ar: as linhas continuam pendentes e saem quando ele voltar (RNF04).
      log.warn("Outbox adiado: {}", e.toString());
    }
  }

  private void publicarLote() {
    List<Linha> linhas =
        jdbc.query(
            """
            select id, topic, msg_key, body, traceparent from outbox_event
            where published_at is null order by created_at limit 100
            for update skip locked
            """,
            (rs, i) ->
                new Linha(
                    rs.getObject("id", UUID.class),
                    rs.getString("topic"),
                    rs.getString("msg_key"),
                    rs.getString("body"),
                    rs.getString("traceparent")));
    for (Linha l : linhas) {
      try {
        var registro = new ProducerRecord<>(l.topic(), l.key(), l.body());
        if (l.traceparent() != null) {
          registro.headers().add("traceparent", l.traceparent().getBytes(StandardCharsets.UTF_8));
        }
        kafka.send(registro).get(10, TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new IllegalStateException("Falha ao publicar em " + l.topic(), e);
      }
      jdbc.update("update outbox_event set published_at = now() where id = ?", l.id());
    }
  }

  private record Linha(UUID id, String topic, String key, String body, String traceparent) {}
}
