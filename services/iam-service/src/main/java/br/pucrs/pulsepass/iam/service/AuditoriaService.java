package br.pucrs.pulsepass.iam.service;

import br.pucrs.pulsepass.iam.service.Diretorio.EventoAuditavel;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Histórico de acessos e decisões (US06). A chave única impede duplicar a leitura repetida. */
@Service
public class AuditoriaService {

  public static final String ORIGEM_KEYCLOAK = "KEYCLOAK";
  public static final String ORIGEM_IAM = "IAM";

  private final JdbcTemplate jdbc;

  public AuditoriaService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Devolve true se o evento era novo. */
  public boolean registrar(String origem, EventoAuditavel e) {
    return jdbc.update(
            """
            insert into auditoria(id, origem, tipo, usuario_id, ip, detalhes, ocorrido_em, chave)
            values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (chave) do nothing
            """,
            UUID.randomUUID(),
            origem,
            e.tipo(),
            e.usuarioId(),
            e.ip(),
            e.detalhes(),
            Timestamp.from(e.quando()),
            e.chave())
        == 1;
  }

  /** Decisão do próprio serviço; cada chamada é um registro novo. */
  public void registrarDecisao(String tipo, String usuarioId, String detalhes) {
    registrar(
        ORIGEM_IAM,
        new EventoAuditavel(
            "iam:" + UUID.randomUUID(), tipo, usuarioId, null, detalhes, Instant.now()));
  }

  public Instant ultimoDoKeycloak() {
    Timestamp t =
        jdbc.queryForObject(
            "select max(ocorrido_em) from auditoria where origem = ?",
            Timestamp.class,
            ORIGEM_KEYCLOAK);
    return t == null ? null : t.toInstant();
  }

  public List<Map<String, Object>> listar(String tipo, String usuarioId, int limite) {
    var sql =
        new StringBuilder(
            "select id, origem, tipo, usuario_id, ip, detalhes, ocorrido_em from auditoria where 1=1");
    List<Object> args = new ArrayList<>();
    if (tipo != null && !tipo.isBlank()) {
      sql.append(" and tipo = ?");
      args.add(tipo);
    }
    if (usuarioId != null && !usuarioId.isBlank()) {
      sql.append(" and usuario_id = ?");
      args.add(usuarioId);
    }
    sql.append(" order by ocorrido_em desc limit ?");
    args.add(limite);
    return jdbc.queryForList(sql.toString(), args.toArray());
  }
}
