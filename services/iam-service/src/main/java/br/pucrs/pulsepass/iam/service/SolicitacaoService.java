package br.pucrs.pulsepass.iam.service;

import br.pucrs.pulsepass.common.Conflito;
import br.pucrs.pulsepass.common.Invalido;
import br.pucrs.pulsepass.common.NaoEncontrado;
import br.pucrs.pulsepass.common.Outbox;
import br.pucrs.pulsepass.common.TransicaoInvalida;
import br.pucrs.pulsepass.iam.domain.Cnpj;
import br.pucrs.pulsepass.iam.domain.SolicitacaoStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cadastro de organizador com aprovação de um admin (US05). */
@Service
public class SolicitacaoService {

  public static final String TOPICO_USUARIO = "iam.usuario.v1";
  static final String PAPEL_ORGANIZADOR = "organizador";

  private final JdbcTemplate jdbc;
  private final Outbox outbox;
  private final Diretorio diretorio;
  private final AuditoriaService auditoria;

  public SolicitacaoService(
      JdbcTemplate jdbc, Outbox outbox, Diretorio diretorio, AuditoriaService auditoria) {
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.diretorio = diretorio;
    this.auditoria = auditoria;
  }

  @Transactional
  public UUID solicitar(String usuarioId, String razaoSocial, String cnpj, String contato) {
    if (razaoSocial == null || razaoSocial.isBlank() || razaoSocial.length() > 200) {
      throw new Invalido("razaoSocial é obrigatória (até 200 caracteres)");
    }
    if (contato == null || contato.isBlank() || contato.length() > 200) {
      throw new Invalido("contato é obrigatório (até 200 caracteres)");
    }
    if (!Cnpj.valido(cnpj)) {
      throw new Invalido("CNPJ inválido");
    }
    UUID id = UUID.randomUUID();
    try {
      jdbc.update(
          """
          insert into solicitacao_organizador(id, usuario_id, razao_social, cnpj, contato, status)
          values (?, ?, ?, ?, ?, ?)
          """,
          id,
          usuarioId,
          razaoSocial.trim(),
          Cnpj.normalizar(cnpj),
          contato.trim(),
          SolicitacaoStatus.PENDENTE.name());
    } catch (DuplicateKeyException e) {
      throw new Conflito("já existe uma solicitação pendente para este usuário");
    }
    auditoria.registrarDecisao("ORGANIZADOR_SOLICITADO", usuarioId, "solicitacao=" + id);
    return id;
  }

  /**
   * Aprova ou rejeita. Se o Keycloak recusar a atribuição do papel, a exceção desfaz tudo e a
   * solicitação continua PENDENTE; atribuir de novo depois é inofensivo.
   */
  @Transactional
  public void decidir(UUID id, String adminId, boolean aprovar, String motivo) {
    var linha =
        jdbc.queryForList(
            "select usuario_id, status from solicitacao_organizador where id = ? for update", id);
    if (linha.isEmpty()) {
      throw new NaoEncontrado("solicitação não encontrada");
    }
    String usuarioId = (String) linha.get(0).get("usuario_id");
    SolicitacaoStatus novo = aprovar ? SolicitacaoStatus.APROVADA : SolicitacaoStatus.REJEITADA;
    try {
      SolicitacaoStatus.valueOf((String) linha.get(0).get("status")).para(novo);
    } catch (TransicaoInvalida e) {
      throw new Conflito("a solicitação já foi decidida");
    }
    jdbc.update(
        "update solicitacao_organizador set status = ?, decidida_em = now(), decidida_por = ?, motivo = ? where id = ?",
        novo.name(),
        adminId,
        motivo,
        id);
    if (aprovar) {
      diretorio.atribuirPapel(usuarioId, PAPEL_ORGANIZADOR);
      outbox.add(
          TOPICO_USUARIO,
          usuarioId,
          "OrganizadorAprovado",
          Map.of("usuarioId", usuarioId, "solicitacaoId", id));
    }
    auditoria.registrarDecisao(
        aprovar ? "ORGANIZADOR_APROVADO" : "ORGANIZADOR_REJEITADO",
        usuarioId,
        "solicitacao=" + id + " decidida por " + adminId);
  }

  public List<Map<String, Object>> listar(String status) {
    String sql =
        "select id, usuario_id, razao_social, cnpj, contato, status, criada_em, decidida_em, motivo from solicitacao_organizador";
    if (status == null || status.isBlank()) {
      return jdbc.queryForList(sql + " order by criada_em desc");
    }
    return jdbc.queryForList(
        sql + " where status = ? order by criada_em desc",
        SolicitacaoStatus.valueOf(status).name());
  }

  public Map<String, Object> minha(String usuarioId) {
    return jdbc
        .queryForList(
            """
            select id, razao_social, cnpj, status, criada_em, decidida_em, motivo
            from solicitacao_organizador where usuario_id = ? order by criada_em desc limit 1
            """,
            usuarioId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new NaoEncontrado("nenhuma solicitação"));
  }
}
