package br.pucrs.pulsepass.apigateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Ponto único de entrada (ADR-005): valida assinatura e expiração do JWT (401). O catálogo é
 * público; o RBAC fino é reaplicado em cada serviço (defesa em profundidade, RNF13).
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

  @Bean
  SecurityWebFilterChain filtros(ServerHttpSecurity http) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(
            a ->
                a.pathMatchers("/actuator/health/**")
                    .permitAll()
                    .pathMatchers(
                        HttpMethod.GET, "/api/eventos", "/api/eventos/*", "/api/eventos/*/assentos")
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
        .build();
  }
}
