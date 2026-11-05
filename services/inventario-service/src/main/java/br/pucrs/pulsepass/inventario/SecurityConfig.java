package br.pucrs.pulsepass.inventario;

import br.pucrs.pulsepass.common.security.KeycloakRoleConverter;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** RBAC (RNF13): catálogo público, cadastro só do Organizador, reservas só do Cliente. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  SecurityFilterChain filtros(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health/**", "/error")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/eventos/*/vendas")
                    .hasRole("ORGANIZADOR")
                    .requestMatchers(
                        HttpMethod.GET, "/eventos", "/eventos/*", "/eventos/*/assentos")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/eventos/**")
                    .hasRole("ORGANIZADOR")
                    .requestMatchers("/reservas/**")
                    .hasRole("CLIENTE")
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            o -> o.jwt(j -> j.jwtAuthenticationConverter(new KeycloakRoleConverter())));
    return http.build();
  }

  @Bean
  NewTopic topicoReserva() {
    return TopicBuilder.name("inventario.reserva.v1").partitions(6).replicas(1).build();
  }

  @Bean
  NewTopic topicoAssento() {
    return TopicBuilder.name("inventario.assento.v1").partitions(6).replicas(1).build();
  }
}
