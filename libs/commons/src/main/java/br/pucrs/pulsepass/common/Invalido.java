package br.pucrs.pulsepass.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Resposta HTTP BAD_REQUEST tratada pelo Spring MVC sem handler extra. */
public class Invalido extends ResponseStatusException {

  public Invalido(String mensagem) {
    super(HttpStatus.BAD_REQUEST, mensagem);
  }
}
