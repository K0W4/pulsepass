package br.pucrs.pulsepass.inventario.service;

import br.pucrs.pulsepass.common.NaoEncontrado;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Consultas do catálogo (US11, US12, US14, US31). A lista de eventos usa cache no Redis (RNF06).
 */
@Service
public class CatalogoService {

  private static final Logger log = LoggerFactory.getLogger(CatalogoService.class);
  private static final String CHAVE = "catalogo:eventos";

  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;

  public CatalogoService(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.redis = redis;
    this.mapper = mapper;
  }

  public List<Map<String, Object>> eventos() {
    try {
      String json = redis.opsForValue().get(CHAVE);
      if (json != null) {
        return mapper.readValue(json, new TypeReference<>() {});
      }
    } catch (DataAccessException | JsonProcessingException e) {
      log.warn("Cache do catálogo indisponível: {}", e.toString());
    }
    List<Map<String, Object>> lista =
        jdbc.queryForList(
            "select id, nome, local, data_hora, imagem_url from evento where status = 'PUBLICADO' order by data_hora");
    try {
      redis.opsForValue().set(CHAVE, mapper.writeValueAsString(lista), Duration.ofSeconds(30));
    } catch (DataAccessException | JsonProcessingException e) {
      log.warn("Não foi possível gravar o cache do catálogo: {}", e.toString());
    }
    return lista;
  }

  public void invalidar() {
    try {
      redis.delete(CHAVE);
    } catch (DataAccessException e) {
      log.warn("Falha ao invalidar o cache do catálogo: {}", e.toString());
    }
  }

  public Map<String, Object> evento(UUID id) {
    Map<String, Object> evento =
        jdbc
            .queryForList(
                "select id, nome, local, data_hora, imagem_url from evento where id = ? and status = 'PUBLICADO'",
                id)
            .stream()
            .findFirst()
            .orElseThrow(() -> new NaoEncontrado("evento não encontrado"));
    List<Map<String, Object>> lotes =
        jdbc.queryForList(
            """
            select l.id, l.nome, l.preco, l.inicio_venda, l.fim_venda,
              case when exists (select 1 from assento a where a.lote_id = l.id)
                then (select count(*) from assento a where a.lote_id = l.id and a.status = 'LIVRE')
                else l.quantidade - l.qtd_reservada - l.qtd_vendida end as disponivel
            from lote l where l.evento_id = ? order by l.inicio_venda
            """,
            id);
    return Map.of("evento", evento, "lotes", lotes);
  }

  public List<Map<String, Object>> assentos(UUID eventoId) {
    return jdbc.queryForList(
        """
        select a.id, a.codigo, s.nome as setor, a.status, l.id as lote_id, l.preco
        from assento a join setor s on s.id = a.setor_id join lote l on l.id = a.lote_id
        where s.evento_id = ? order by s.nome, a.codigo
        """,
        eventoId);
  }

  /** Total vendido e ocupação por lote, para o painel do organizador (US31). */
  public List<Map<String, Object>> vendas(UUID eventoId, String organizadorId) {
    Integer dono =
        jdbc.queryForObject(
            "select count(*) from evento where id = ? and organizador_id = ?",
            Integer.class,
            eventoId,
            organizadorId);
    if (dono == null || dono == 0) {
      throw new NaoEncontrado("evento não encontrado");
    }
    return jdbc.queryForList(
        """
        select l.id, l.nome, l.quantidade, l.qtd_reservada, l.qtd_vendida,
               l.qtd_vendida * l.preco as total_vendido
        from lote l where l.evento_id = ? order by l.inicio_venda
        """,
        eventoId);
  }
}
