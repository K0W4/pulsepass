package br.pucrs.pulsepass.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Gateway de pagamento simulado (ADR-007): aprova, recusa, atrasa ou falha sob configuração. */
@SpringBootApplication
public class GatewayPagamentoApplication {

  public static void main(String[] args) {
    SpringApplication.run(GatewayPagamentoApplication.class, args);
  }
}
