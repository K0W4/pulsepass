package br.pucrs.pulsepass.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Resposta HTTP CONFLICT tratada pelo Spring MVC sem handler extra. */
public class Conflito extends ResponseStatusException {

  public Conflito(String mensagem) {
    super(HttpStatus.CONFLICT, mensagem);
  }
}
