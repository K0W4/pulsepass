package br.pucrs.pulsepass.emissao.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** QR com identificador assinado (RNF14): impossível de forjar a partir de dados públicos. */
public class QrAssinador {

  private static final String PREFIXO = "PP1";

  private final byte[] segredo;

  public QrAssinador(String segredo) {
    if (segredo == null || segredo.length() < 32) {
      throw new IllegalArgumentException("o segredo do QR precisa de ao menos 32 caracteres");
    }
    this.segredo = segredo.getBytes(StandardCharsets.UTF_8);
  }

  public String gerar(UUID ingressoId) {
    return PREFIXO + "." + ingressoId + "." + assinar(ingressoId.toString());
  }

  /** Devolve o id só se o formato e a assinatura estiverem corretos. */
  public Optional<UUID> verificar(String codigo) {
    if (codigo == null) {
      return Optional.empty();
    }
    String[] partes = codigo.split("\\.");
    if (partes.length != 3 || !PREFIXO.equals(partes[0])) {
      return Optional.empty();
    }
    try {
      UUID id = UUID.fromString(partes[1]);
      boolean ok =
          MessageDigest.isEqual(
              assinar(id.toString()).getBytes(StandardCharsets.UTF_8),
              partes[2].getBytes(StandardCharsets.UTF_8));
      return ok ? Optional.of(id) : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private String assinar(String texto) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(segredo, "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(texto.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }
}
