package br.pucrs.pulsepass.iam.api;

import br.pucrs.pulsepass.common.Invalido;
import br.pucrs.pulsepass.iam.service.AuditoriaService;
import br.pucrs.pulsepass.iam.service.SolicitacaoService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IamController {

  public record SolicitacaoRequest(String razaoSocial, String cnpj, String contato) {}

  public record RejeicaoRequest(String motivo) {}

  private final SolicitacaoService solicitacoes;
  private final AuditoriaService auditoria;

  public IamController(SolicitacaoService solicitacoes, AuditoriaService auditoria) {
    this.solicitacoes = solicitacoes;
    this.auditoria = auditoria;
  }

  @PostMapping("/iam/organizadores/solicitacoes")
  public ResponseEntity<Map<String, UUID>> solicitar(
      @AuthenticationPrincipal Jwt jwt, @RequestBody SolicitacaoRequest r) {
    UUID id = solicitacoes.solicitar(jwt.getSubject(), r.razaoSocial(), r.cnpj(), r.contato());
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
  }

  @GetMapping("/iam/minha-solicitacao")
  public Map<String, Object> minha(@AuthenticationPrincipal Jwt jwt) {
    return solicitacoes.minha(jwt.getSubject());
  }

  @GetMapping("/iam/organizadores/solicitacoes")
  public List<Map<String, Object>> listar(@RequestParam(required = false) String status) {
    try {
      return solicitacoes.listar(status);
    } catch (IllegalArgumentException e) {
      throw new Invalido("status deve ser PENDENTE, APROVADA ou REJEITADA");
    }
  }

  @PostMapping("/iam/organizadores/solicitacoes/{id}/aprovar")
  public ResponseEntity<Void> aprovar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    solicitacoes.decidir(id, jwt.getSubject(), true, null);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/iam/organizadores/solicitacoes/{id}/rejeitar")
  public ResponseEntity<Void> rejeitar(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestBody(required = false) RejeicaoRequest r) {
    solicitacoes.decidir(id, jwt.getSubject(), false, r == null ? null : r.motivo());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/iam/auditoria")
  public List<Map<String, Object>> auditoria(
      @RequestParam(required = false) String tipo,
      @RequestParam(required = false) String usuario,
      @RequestParam(defaultValue = "100") int limite) {
    if (limite < 1 || limite > 1000) {
      throw new Invalido("limite deve estar entre 1 e 1000");
    }
    return auditoria.listar(tipo, usuario, limite);
  }
}
