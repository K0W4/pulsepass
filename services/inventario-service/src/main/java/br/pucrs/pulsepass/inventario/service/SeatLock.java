package br.pucrs.pulsepass.inventario.service;

import br.pucrs.pulsepass.common.Conflito;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Filtro de contenção no Redis (ADR-002). Quem decide o assento é o UPDATE condicional no
 * PostgreSQL; se o Redis cair, a reserva segue sem o filtro.
 */
@Component
public class SeatLock {

  private static final Logger log = LoggerFactory.getLogger(SeatLock.class);
  private static final Duration TTL = Duration.ofSeconds(10);

  private final StringRedisTemplate redis;

  public SeatLock(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public List<String> adquirir(Collection<UUID> assentos) {
    List<String> chaves = new ArrayList<>();
    try {
      for (UUID assento : assentos.stream().sorted().toList()) {
        String chave = "lock:assento:" + assento;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(chave, "1", TTL))) {
          liberar(chaves);
          throw new Conflito("assento indisponível");
        }
        chaves.add(chave);
      }
    } catch (DataAccessException e) {
      log.warn("Redis indisponível, reservando sem filtro de contenção: {}", e.toString());
      return List.of();
    }
    return chaves;
  }

  public void liberar(List<String> chaves) {
    if (chaves.isEmpty()) {
      return;
    }
    try {
      redis.delete(chaves);
    } catch (DataAccessException e) {
      log.warn("Falha ao liberar chaves no Redis: {}", e.toString());
    }
  }
}
