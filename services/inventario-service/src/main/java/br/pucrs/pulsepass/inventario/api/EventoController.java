package br.pucrs.pulsepass.inventario.api;

import br.pucrs.pulsepass.common.Invalido;
import br.pucrs.pulsepass.inventario.api.Dtos.EventoRequest;
import br.pucrs.pulsepass.inventario.api.Dtos.FilaRequest;
import br.pucrs.pulsepass.inventario.api.Dtos.LoteRequest;
import br.pucrs.pulsepass.inventario.api.Dtos.SetorRequest;
import br.pucrs.pulsepass.inventario.domain.FiltroCatalogo.Criterios;
import br.pucrs.pulsepass.inventario.service.CatalogoService;
import br.pucrs.pulsepass.inventario.service.EventoService;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EventoController {

  private final EventoService eventos;
  private final CatalogoService catalogo;

  public EventoController(EventoService eventos, CatalogoService catalogo) {
    this.eventos = eventos;
    this.catalogo = catalogo;
  }

  @PostMapping("/eventos")
  public ResponseEntity<Map<String, UUID>> criar(
      @AuthenticationPrincipal Jwt jwt, @RequestBody EventoRequest r) {
    return criado(eventos.criar(jwt.getSubject(), r));
  }

  @PostMapping("/eventos/{id}/lotes")
  public ResponseEntity<Map<String, UUID>> criarLote(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody LoteRequest r) {
    return criado(eventos.criarLote(id, jwt.getSubject(), r));
  }

  @PostMapping("/eventos/{id}/setores")
  public ResponseEntity<Map<String, UUID>> criarSetor(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody SetorRequest r) {
    return criado(eventos.criarSetor(id, jwt.getSubject(), r));
  }

  /** Catálogo público; com filtros opcionais de nome, local e datas (US13). */
  @GetMapping("/eventos")
  public List<Map<String, Object>> listar(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String local,
      @RequestParam(required = false) String de,
      @RequestParam(required = false) String ate) {
    if (q == null && local == null && de == null && ate == null) {
      return catalogo.eventos();
    }
    return catalogo.eventos(new Criterios(q, local, data(de, "de"), data(ate, "ate")));
  }

  @GetMapping("/eventos/meus")
  public List<Map<String, Object>> meus(@AuthenticationPrincipal Jwt jwt) {
    return catalogo.meus(jwt.getSubject());
  }

  @PostMapping("/eventos/{id}/publicar")
  public ResponseEntity<Void> publicar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    eventos.publicar(id, jwt.getSubject(), true);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/eventos/{id}/despublicar")
  public ResponseEntity<Void> despublicar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    eventos.publicar(id, jwt.getSubject(), false);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/eventos/{id}/fila-ativa")
  public ResponseEntity<Void> filaAtiva(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody FilaRequest r) {
    eventos.filaAtiva(id, jwt.getSubject(), r.ativa());
    return ResponseEntity.noContent().build();
  }

  private static LocalDate data(String valor, String campo) {
    if (valor == null || valor.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(valor);
    } catch (DateTimeParseException e) {
      throw new Invalido("'" + campo + "' deve estar no formato AAAA-MM-DD");
    }
  }

  @GetMapping("/eventos/{id}")
  public Map<String, Object> detalhar(@PathVariable UUID id) {
    return catalogo.evento(id);
  }

  @GetMapping("/eventos/{id}/assentos")
  public List<Map<String, Object>> assentos(@PathVariable UUID id) {
    return catalogo.assentos(id);
  }

  @GetMapping("/eventos/{id}/vendas")
  public List<Map<String, Object>> vendas(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return catalogo.vendas(id, jwt.getSubject());
  }

  private static ResponseEntity<Map<String, UUID>> criado(UUID id) {
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
  }
}
