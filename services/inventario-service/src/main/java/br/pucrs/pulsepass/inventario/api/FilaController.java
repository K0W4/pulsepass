package br.pucrs.pulsepass.inventario.api;

import br.pucrs.pulsepass.inventario.service.FilaService;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FilaController {

  private final FilaService fila;

  public FilaController(FilaService fila) {
    this.fila = fila;
  }

  @PostMapping("/eventos/{id}/fila")
  public Map<String, Object> entrar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return fila.entrar(id, jwt.getSubject());
  }

  @GetMapping("/eventos/{id}/fila")
  public Map<String, Object> situacao(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return fila.situacao(id, jwt.getSubject());
  }
}
