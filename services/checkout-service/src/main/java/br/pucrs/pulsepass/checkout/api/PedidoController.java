package br.pucrs.pulsepass.checkout.api;

import br.pucrs.pulsepass.checkout.service.PagamentoService;
import br.pucrs.pulsepass.checkout.service.PedidoService;
import br.pucrs.pulsepass.common.Invalido;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PedidoController {

  public record PedidoRequest(UUID reservaId) {}

  public record PagamentoRequest(UUID pedidoId) {}

  private final PedidoService pedidos;
  private final PagamentoService pagamentos;

  public PedidoController(PedidoService pedidos, PagamentoService pagamentos) {
    this.pedidos = pedidos;
    this.pagamentos = pagamentos;
  }

  @PostMapping("/pedidos")
  public ResponseEntity<Map<String, Object>> abrir(
      @AuthenticationPrincipal Jwt jwt, @RequestBody PedidoRequest r) {
    if (r.reservaId() == null) {
      throw new Invalido("reservaId é obrigatório");
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(pedidos.abrir(jwt.getSubject(), r.reservaId()));
  }

  @GetMapping("/pedidos/{id}")
  public Map<String, Object> detalhar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return pedidos.detalhar(id, jwt.getSubject());
  }

  @PostMapping("/pagamentos")
  public ResponseEntity<String> pagar(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) String chave,
      @RequestBody PagamentoRequest r) {
    if (chave == null || chave.isBlank()) {
      throw new Invalido("cabeçalho Idempotency-Key é obrigatório");
    }
    if (r.pedidoId() == null) {
      throw new Invalido("pedidoId é obrigatório");
    }
    var resposta = pagamentos.pagar(jwt.getSubject(), chave, r.pedidoId());
    return ResponseEntity.status(resposta.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(resposta.corpo());
  }
}
