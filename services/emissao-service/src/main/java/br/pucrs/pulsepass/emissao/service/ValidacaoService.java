package br.pucrs.pulsepass.emissao.service;

import br.pucrs.pulsepass.common.Outbox;
import br.pucrs.pulsepass.emissao.domain.IngressoStatus;
import br.pucrs.pulsepass.emissao.domain.QrAssinador;
import br.pucrs.pulsepass.emissao.service.Eventos.IngressoUtilizado;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validação na portaria (US28, US29): a contagem de linhas do UPDATE decide quem entra. */
@Service
public class ValidacaoService {

  public record Resultado(int httpStatus, Map<String, Object> corpo) {}

  private final JdbcTemplate jdbc;
  private final Outbox outbox;
  private final QrAssinador qr;

  public ValidacaoService(JdbcTemplate jdbc, Outbox outbox, QrAssinador qr) {
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.qr = qr;
  }

  @Transactional
  public Resultado validar(String operadorId, String codigo) {
    Optional<UUID> id = qr.verificar(codigo);
    if (id.isEmpty()) {
      registrar(null, operadorId, "INVALIDO");
      return new Resultado(422, Map.of("resultado", "INVALIDO", "motivo", "QR_ADULTERADO"));
    }
    IngressoStatus.EMITIDO.para(IngressoStatus.UTILIZADO);
    int liberado =
        jdbc.update(
            """
            update ingresso set status = 'UTILIZADO', utilizado_em = now(), utilizado_por = ?
            where id = ? and status = 'EMITIDO'
            """,
            operadorId,
            id.get());
    var dados =
        jdbc.queryForList(
            "select evento_nome, descricao, status, utilizado_em from ingresso where id = ?",
            id.get());
    if (dados.isEmpty()) {
      registrar(null, operadorId, "INVALIDO");
      return new Resultado(422, Map.of("resultado", "INVALIDO", "motivo", "INGRESSO_INEXISTENTE"));
    }
    var i = dados.get(0);
    if (liberado == 1) {
      registrar(id.get(), operadorId, "LIBERADO");
      outbox.add(
          Eventos.TOPICO_INGRESSO,
          pedidoDe(id.get()),
          "IngressoUtilizado",
          new IngressoUtilizado(id.get(), operadorId, Instant.now()));
      return new Resultado(
          200, corpo("LIBERADO", i.get("evento_nome"), i.get("descricao"), "Entrada liberada"));
    }
    registrar(id.get(), operadorId, "JA_UTILIZADO");
    var resposta =
        corpo("JA_UTILIZADO", i.get("evento_nome"), i.get("descricao"), "Ingresso já utilizado");
    resposta.put("primeiraLeitura", i.get("utilizado_em"));
    return new Resultado(409, resposta);
  }

  private Map<String, Object> corpo(
      String resultado, Object evento, Object descricao, String mensagem) {
    var m = new LinkedHashMap<String, Object>();
    m.put("resultado", resultado);
    m.put("mensagem", mensagem);
    m.put("evento", evento);
    m.put("descricao", descricao);
    return m;
  }

  private String pedidoDe(UUID ingressoId) {
    return jdbc.queryForObject(
        "select pedido_id::text from ingresso where id = ?", String.class, ingressoId);
  }

  private void registrar(UUID ingressoId, String operadorId, String resultado) {
    jdbc.update(
        "insert into validacao_acesso(id, ingresso_id, operador_id, resultado) values (?, ?, ?, ?)",
        UUID.randomUUID(),
        ingressoId,
        operadorId,
        resultado);
  }
}
