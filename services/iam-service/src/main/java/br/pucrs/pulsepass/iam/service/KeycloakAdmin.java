package br.pucrs.pulsepass.iam.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Admin API do Keycloak com a conta de serviço do client {@code pulsepass-iam} (client
 * credentials). Só dois usos: atribuir papel e ler eventos de acesso.
 */
@Component
public class KeycloakAdmin implements Diretorio {

  /** Eventos que interessam à auditoria de acessos suspeitos (US06). */
  static final List<String> TIPOS =
      List.of(
          "LOGIN",
          "LOGIN_ERROR",
          "LOGOUT",
          "REGISTER",
          "UPDATE_PASSWORD",
          "RESET_PASSWORD",
          "UPDATE_EMAIL");

  private final RestClient http;
  private final String realm;
  private final String clientId;
  private final String segredo;

  private String token;
  private Instant tokenValidoAte = Instant.EPOCH;

  public KeycloakAdmin(
      @Value("${iam.keycloak.url}") String url,
      @Value("${iam.keycloak.realm}") String realm,
      @Value("${iam.keycloak.client-id}") String clientId,
      @Value("${iam.keycloak.client-secret}") String segredo) {
    var fabrica = new org.springframework.http.client.SimpleClientHttpRequestFactory();
    fabrica.setConnectTimeout(2_000);
    fabrica.setReadTimeout(5_000);
    this.http = RestClient.builder().baseUrl(url).requestFactory(fabrica).build();
    this.realm = realm;
    this.clientId = clientId;
    this.segredo = segredo;
  }

  @Override
  public void atribuirPapel(String usuarioId, String papel) {
    try {
      JsonNode funcao =
          http.get()
              .uri("/admin/realms/{r}/roles/{p}", realm, papel)
              .headers(h -> h.setBearerAuth(token()))
              .retrieve()
              .body(JsonNode.class);
      http.post()
          .uri("/admin/realms/{r}/users/{u}/role-mappings/realm", realm, usuarioId)
          .headers(h -> h.setBearerAuth(token()))
          .contentType(MediaType.APPLICATION_JSON)
          .body(List.of(Map.of("id", funcao.path("id").asText(), "name", papel)))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      throw new DiretorioIndisponivel("Keycloak recusou a atribuição do papel", e);
    }
  }

  @Override
  public List<EventoAuditavel> eventos(Instant desde) {
    try {
      var uri =
          UriComponentsBuilder.fromPath("/admin/realms/{r}/events")
              .queryParam("dateFrom", desde.atZone(ZoneOffset.UTC).toLocalDate().toString())
              .queryParam("max", 500);
      TIPOS.forEach(t -> uri.queryParam("type", t));
      JsonNode lista =
          http.get()
              .uri(uri.buildAndExpand(realm).toUriString())
              .headers(h -> h.setBearerAuth(token()))
              .retrieve()
              .body(JsonNode.class);
      List<EventoAuditavel> saida = new ArrayList<>();
      if (lista == null) {
        return saida;
      }
      for (JsonNode e : lista) {
        Instant quando = Instant.ofEpochMilli(e.path("time").asLong());
        if (quando.isBefore(desde)) {
          continue;
        }
        String tipo = e.path("type").asText();
        String usuario = e.path("userId").asText(null);
        String ip = e.path("ipAddress").asText(null);
        // só o que a auditoria precisa: sem username nem e-mail (minimização, RNF15)
        String erro = e.path("error").asText(null);
        String detalhes =
            "client=" + e.path("clientId").asText("?") + (erro == null ? "" : " erro=" + erro);
        String chave =
            sha256(
                quando.toEpochMilli()
                    + "|"
                    + tipo
                    + "|"
                    + usuario
                    + "|"
                    + e.path("sessionId").asText("")
                    + "|"
                    + ip);
        saida.add(new EventoAuditavel(chave, tipo, usuario, ip, detalhes, quando));
      }
      return saida;
    } catch (RestClientException e) {
      throw new DiretorioIndisponivel("Keycloak não devolveu os eventos", e);
    }
  }

  /** Token da conta de serviço, reaproveitado até 10 s antes de vencer. */
  private synchronized String token() {
    if (token != null && Instant.now().isBefore(tokenValidoAte)) {
      return token;
    }
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", segredo);
    JsonNode r =
        http.post()
            .uri("/realms/{r}/protocol/openid-connect/token", realm)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(JsonNode.class);
    token = r.path("access_token").asText();
    tokenValidoAte = Instant.now().plusSeconds(Math.max(0, r.path("expires_in").asLong(60) - 10));
    return token;
  }

  private static String sha256(String texto) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
