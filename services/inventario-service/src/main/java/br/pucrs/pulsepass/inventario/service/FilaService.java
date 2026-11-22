package br.pucrs.pulsepass.inventario.service;

import br.pucrs.pulsepass.common.Conflito;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Fila virtual por ordem de chegada (US18, RNF23). O Redis só decide quem pode tentar reservar; a
 * verdade da reserva continua no PostgreSQL (regra 4 do projeto).
 */
@Service
public class FilaService {

  private static final Logger log = LoggerFactory.getLogger(FilaService.class);
  static final Duration VALIDADE_ADMISSAO = Duration.ofMinutes(5);

  private final StringRedisTemplate redis;
  private final JdbcTemplate jdbc;

  public FilaService(StringRedisTemplate redis, JdbcTemplate jdbc) {
    this.redis = redis;
    this.jdbc = jdbc;
  }

  private static String fila(UUID evento) {
    return "fila:" + evento;
  }

  private static String admissao(UUID evento, String cliente) {
    return "fila:ok:" + evento + ":" + cliente;
  }

  public boolean ativa(UUID eventoId) {
    return jdbc
        .query("select fila_ativa from evento where id = ?", (rs, i) -> rs.getBoolean(1), eventoId)
        .stream()
        .findFirst()
        .orElse(false);
  }

  /** Entra na fila; entrar de novo mantém a posição original. */
  public Map<String, Object> entrar(UUID eventoId, String clienteId) {
    if (!ativa(eventoId)) {
      throw new Conflito("este evento não tem fila virtual ativa");
    }
    if (!Boolean.TRUE.equals(redis.hasKey(admissao(eventoId, clienteId)))) {
      Long ordem = redis.opsForValue().increment("fila:seq:" + eventoId);
      redis.opsForZSet().addIfAbsent(fila(eventoId), clienteId, ordem == null ? 0 : ordem);
    }
    return situacao(eventoId, clienteId);
  }

  /** ADMITIDO, AGUARDANDO (com a posição, a partir de 1) ou FORA. */
  public Map<String, Object> situacao(UUID eventoId, String clienteId) {
    if (Boolean.TRUE.equals(redis.hasKey(admissao(eventoId, clienteId)))) {
      return Map.of("status", "ADMITIDO");
    }
    Long posicao = redis.opsForZSet().rank(fila(eventoId), clienteId);
    if (posicao == null) {
      return Map.of("status", "FORA");
    }
    return Map.of("status", "AGUARDANDO", "posicao", posicao + 1);
  }

  /**
   * Admite os {@code n} primeiros; ZPOPMIN é atômico, então réplicas não admitem o mesmo cliente.
   */
  public int admitir(UUID eventoId, int n) {
    var primeiros = redis.opsForZSet().popMin(fila(eventoId), n);
    if (primeiros == null) {
      return 0;
    }
    primeiros.forEach(
        t -> redis.opsForValue().set(admissao(eventoId, t.getValue()), "1", VALIDADE_ADMISSAO));
    return primeiros.size();
  }

  /** A fila é proteção, não regra de negócio: se o Redis cair, a compra não é bloqueada. */
  public boolean podeReservar(UUID eventoId, String clienteId) {
    if (eventoId == null || !ativa(eventoId)) {
      return true;
    }
    try {
      return Boolean.TRUE.equals(redis.hasKey(admissao(eventoId, clienteId)));
    } catch (DataAccessException e) {
      log.warn("Redis indisponível; fila virtual ignorada: {}", e.toString());
      return true;
    }
  }

  /** Uma admissão vale uma reserva. */
  public void consumir(UUID eventoId, String clienteId) {
    try {
      redis.delete(admissao(eventoId, clienteId));
    } catch (DataAccessException e) {
      log.warn("Não foi possível consumir a admissão: {}", e.toString());
    }
  }

  public List<UUID> eventosComFila() {
    return jdbc.queryForList("select id from evento where fila_ativa", UUID.class);
  }

  /** Só uma réplica admite por intervalo: a primeira a criar a chave de compasso. */
  boolean minhaVez(UUID eventoId, Duration intervalo) {
    return Boolean.TRUE.equals(
        redis.opsForValue().setIfAbsent("fila:tick:" + eventoId, "1", intervalo));
  }
}
