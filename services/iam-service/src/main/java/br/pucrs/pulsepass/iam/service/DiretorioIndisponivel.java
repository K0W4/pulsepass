package br.pucrs.pulsepass.iam.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** O provedor de identidade não respondeu ou recusou: a operação inteira deve ser desfeita. */
public class DiretorioIndisponivel extends ResponseStatusException {

  public DiretorioIndisponivel(String mensagem, Throwable causa) {
    super(HttpStatus.SERVICE_UNAVAILABLE, mensagem, causa);
  }
}
