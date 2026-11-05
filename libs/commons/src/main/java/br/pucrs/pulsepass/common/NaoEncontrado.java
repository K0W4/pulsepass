package br.pucrs.pulsepass.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Resposta HTTP NOT_FOUND tratada pelo Spring MVC sem handler extra. */
public class NaoEncontrado extends ResponseStatusException {

  public NaoEncontrado(String mensagem) {
    super(HttpStatus.NOT_FOUND, mensagem);
  }
}
