package br.pucrs.pulsepass.inventario.api;

import br.pucrs.pulsepass.inventario.api.Dtos.ReservaRequest;
import br.pucrs.pulsepass.inventario.api.Dtos.ReservaResponse;
import br.pucrs.pulsepass.inventario.service.ReservaService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservaController {

  private final ReservaService reservas;

  public ReservaController(ReservaService reservas) {
    this.reservas = reservas;
  }

  @PostMapping("/reservas")
  public ResponseEntity<ReservaResponse> reservar(
      @AuthenticationPrincipal Jwt jwt, @RequestBody ReservaRequest r) {
    var resposta = reservas.reservar(jwt.getSubject(), r.eventoId(), r.assentoIds(), r.itens());
    return ResponseEntity.status(HttpStatus.CREATED).body(resposta);
  }

  @DeleteMapping("/reservas/{id}")
  public ResponseEntity<Void> cancelar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    reservas.cancelar(id, jwt.getSubject());
    return ResponseEntity.noContent().build();
  }
}
