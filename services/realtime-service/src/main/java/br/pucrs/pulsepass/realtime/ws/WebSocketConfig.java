package br.pucrs.pulsepass.realtime.ws;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Canais públicos por evento (assentos, US14) e canais por pedido, que exigem JWT do papel cliente
 * (US27). O navegador não envia cabeçalho no WebSocket, então o token vai na query.
 *
 * <p>ponytail: o token na URL pode aparecer em logs de acesso; para produção, troque por um ticket
 * de uso único trocado por POST antes de abrir o socket.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private static final Pattern ID = Pattern.compile("[0-9a-fA-F-]{36}");

  private final Canais canais;
  private final JwtDecoder jwt;

  public WebSocketConfig(Canais canais, JwtDecoder jwt) {
    this.canais = canais;
    this.jwt = jwt;
  }

  @Bean
  static JwtDecoder jwtDecoder(@Value("${realtime.jwk-set-uri}") String jwkSetUri) {
    return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(new Entrada(canais), "/realtime/eventos/*", "/realtime/pedidos/*")
        .addInterceptors(new Porteiro(jwt))
        // sem cookies nem credenciais: o acesso é decidido pelo token, não pela origem
        .setAllowedOriginPatterns("*");
  }

  /** Registra a sessão no canal que o porteiro decidiu. */
  static final class Entrada extends TextWebSocketHandler {
    private final Canais canais;

    Entrada(Canais canais) {
      this.canais = canais;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession sessao) {
      canais.adicionar((String) sessao.getAttributes().get("canal"), sessao);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession sessao, CloseStatus status) {
      canais.remover((String) sessao.getAttributes().get("canal"), sessao);
    }
  }

  /** Valida o caminho e, nos canais de pedido, o JWT. */
  static final class Porteiro implements HandshakeInterceptor {
    private final JwtDecoder jwt;

    Porteiro(JwtDecoder jwt) {
      this.jwt = jwt;
    }

    @Override
    public boolean beforeHandshake(
        ServerHttpRequest req,
        ServerHttpResponse resp,
        WebSocketHandler handler,
        Map<String, Object> atributos) {
      String[] partes = req.getURI().getPath().split("/"); // ["", "realtime", tipo, id]
      if (partes.length != 4 || !ID.matcher(partes[3]).matches()) {
        resp.setStatusCode(HttpStatus.NOT_FOUND);
        return false;
      }
      if ("pedidos".equals(partes[2]) && !clienteAutenticado(req)) {
        resp.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
      }
      atributos.put("canal", partes[2] + "/" + partes[3]);
      return true;
    }

    private boolean clienteAutenticado(ServerHttpRequest req) {
      String token =
          UriComponentsBuilder.fromUri(req.getURI()).build().getQueryParams().getFirst("token");
      if (token == null || token.isBlank()) {
        return false;
      }
      try {
        Object acesso = jwt.decode(token).getClaims().get("realm_access");
        return acesso instanceof Map<?, ?> m
            && m.get("roles") instanceof List<?> papeis
            && papeis.contains("cliente");
      } catch (JwtException e) {
        return false;
      }
    }

    @Override
    public void afterHandshake(
        ServerHttpRequest req, ServerHttpResponse resp, WebSocketHandler h, Exception e) {}
  }
}
