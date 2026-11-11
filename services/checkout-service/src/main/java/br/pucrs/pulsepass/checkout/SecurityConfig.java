package br.pucrs.pulsepass.checkout;

import br.pucrs.pulsepass.common.security.KeycloakRoleConverter;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** RBAC (RNF13): pedidos e pagamentos são do papel Cliente. */
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
                    .requestMatchers("/pedidos/**", "/pagamentos/**")
                    .hasRole("CLIENTE")
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            o -> o.jwt(j -> j.jwtAuthenticationConverter(new KeycloakRoleConverter())));
    return http.build();
  }

  @Bean
  NewTopic topicoPedido() {
    return TopicBuilder.name("checkout.pedido.v1").partitions(6).replicas(1).build();
  }
}
