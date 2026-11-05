package br.pucrs.pulsepass.emissao.api;

import br.pucrs.pulsepass.common.NaoEncontrado;
import br.pucrs.pulsepass.emissao.domain.QrAssinador;
import br.pucrs.pulsepass.emissao.service.PdfIngresso;
import br.pucrs.pulsepass.emissao.service.ValidacaoService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IngressoController {

  public record ValidacaoRequest(String codigo) {}

  private final JdbcTemplate jdbc;
  private final QrAssinador qr;
  private final PdfIngresso pdf;
  private final ValidacaoService validacao;

  public IngressoController(
      JdbcTemplate jdbc, QrAssinador qr, PdfIngresso pdf, ValidacaoService validacao) {
    this.jdbc = jdbc;
    this.qr = qr;
    this.pdf = pdf;
    this.validacao = validacao;
  }

  /** US26: todos os ingressos do cliente, com o código do QR. */
  @GetMapping("/ingressos")
  public List<Map<String, Object>> meus(@AuthenticationPrincipal Jwt jwt) {
    var lista =
        jdbc.queryForList(
            "select id, pedido_id, evento_nome, descricao, status, emitido_em from ingresso where cliente_id = ? order by emitido_em, id",
            jwt.getSubject());
    return lista.stream()
        .map(
            i -> {
              var m = new java.util.LinkedHashMap<>(i);
              m.put("codigo", qr.gerar((UUID) i.get("id")));
              return (Map<String, Object>) m;
            })
        .toList();
  }

  @GetMapping("/ingressos/{id}/pdf")
  public ResponseEntity<byte[]> baixar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    var achado =
        jdbc.queryForList(
            "select id, evento_nome, descricao, emitido_em from ingresso where id = ? and cliente_id = ?",
            id,
            jwt.getSubject());
    if (achado.isEmpty()) {
      throw new NaoEncontrado("ingresso não encontrado");
    }
    byte[] bytes = pdf.gerar(achado.get(0), qr.gerar(id));
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ingresso-" + id + ".pdf\"")
        .body(bytes);
  }

  @PostMapping("/validacoes")
  public ResponseEntity<Map<String, Object>> validar(
      @AuthenticationPrincipal Jwt jwt, @RequestBody ValidacaoRequest r) {
    var resultado = validacao.validar(jwt.getSubject(), r.codigo());
    return ResponseEntity.status(resultado.httpStatus()).body(resultado.corpo());
  }
}
