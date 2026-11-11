package br.pucrs.pulsepass.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

@AutoConfiguration(
    after = {
      KafkaAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      TransactionAutoConfiguration.class
    })
@EnableScheduling
public class CommonsAutoConfiguration {

  @Bean
  Outbox outbox(JdbcTemplate jdbc, ObjectMapper mapper, ObjectProvider<Tracer> tracer) {
    return new Outbox(jdbc, mapper, tracer);
  }

  @Bean
  OutboxRelay outboxRelay(
      JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx) {
    return new OutboxRelay(jdbc, kafka, tx);
  }

  @Bean
  ProcessedEvents processedEvents(JdbcTemplate jdbc) {
    return new ProcessedEvents(jdbc);
  }

  @Bean
  EventReader eventReader(ObjectMapper mapper) {
    return new EventReader(mapper);
  }

  /** Três tentativas e depois o tópico {topico}.{grupo}.dlq (seção 4.5 do Pacote 2). */
  @Bean
  DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, String> kafka,
      @Value("${spring.kafka.consumer.group-id}") String grupo) {
    var recoverer =
        new DeadLetterPublishingRecoverer(
            kafka,
            (registro, erro) -> new TopicPartition(registro.topic() + "." + grupo + ".dlq", -1));
    return new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L));
  }
}
