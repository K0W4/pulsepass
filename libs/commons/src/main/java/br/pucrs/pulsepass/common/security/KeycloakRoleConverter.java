package br.pucrs.pulsepass.common.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Converte os papéis do realm do Keycloak (realm_access.roles) em ROLE_* do Spring Security. */
public class KeycloakRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    Map<String, Object> realm = jwt.getClaimAsMap("realm_access");
    Collection<?> papeis =
        realm != null && realm.get("roles") instanceof Collection<?> c ? c : List.of();
    List<GrantedAuthority> autoridades =
        papeis.stream()
            .<GrantedAuthority>map(
                p -> new SimpleGrantedAuthority("ROLE_" + p.toString().toUpperCase()))
            .toList();
    return new JwtAuthenticationToken(jwt, autoridades, jwt.getSubject());
  }
}
