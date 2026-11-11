package br.pucrs.pulsepass.emissao;

import br.pucrs.pulsepass.common.security.KeycloakRoleConverter;
import br.pucrs.pulsepass.emissao.domain.QrAssinador;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** RBAC (RNF13): ingressos do Cliente, validação do Operador. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  SecurityFilterChain filtros(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health/**", "/actuator/prometheus", "/error")
                    .permitAll()
                    .requestMatchers("/ingressos/**")
                    .hasRole("CLIENTE")
                    .requestMatchers("/validacoes")
                    .hasRole("OPERADOR")
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            o -> o.jwt(j -> j.jwtAuthenticationConverter(new KeycloakRoleConverter())));
    return http.build();
  }

  @Bean
  QrAssinador qrAssinador(@Value("${emissao.qr.secret}") String segredo) {
    return new QrAssinador(segredo);
  }

  @Bean
  NewTopic topicoIngresso() {
    return TopicBuilder.name("emissao.ingresso.v1").partitions(3).replicas(1).build();
  }
}
