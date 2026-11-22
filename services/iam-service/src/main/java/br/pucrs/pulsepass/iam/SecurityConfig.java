package br.pucrs.pulsepass.iam;

import br.pucrs.pulsepass.common.security.KeycloakRoleConverter;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** RBAC (RNF13): o cliente pede, só o admin decide e audita. */
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
                    .requestMatchers(HttpMethod.POST, "/iam/organizadores/solicitacoes")
                    .hasRole("CLIENTE")
                    .requestMatchers(HttpMethod.GET, "/iam/minha-solicitacao")
                    .hasRole("CLIENTE")
                    .requestMatchers("/iam/organizadores/solicitacoes/**", "/iam/auditoria")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            o -> o.jwt(j -> j.jwtAuthenticationConverter(new KeycloakRoleConverter())));
    return http.build();
  }

  @Bean
  NewTopic topicoUsuario() {
    return TopicBuilder.name("iam.usuario.v1").partitions(3).replicas(1).build();
  }
}
