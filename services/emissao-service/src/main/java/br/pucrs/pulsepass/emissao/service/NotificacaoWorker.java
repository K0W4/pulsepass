package br.pucrs.pulsepass.emissao.service;

import br.pucrs.pulsepass.emissao.domain.QrAssinador;
import jakarta.mail.MessagingException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Envia o e-mail com os PDFs (US25). Roda à parte da emissão: SMTP fora do ar atrasa o e-mail, mas
 * nunca impede o ingresso (RNF11). SKIP LOCKED permite várias réplicas sem e-mail duplicado.
 */
@Component
public class NotificacaoWorker {

  private static final Logger log = LoggerFactory.getLogger(NotificacaoWorker.class);

  private record Pendente(UUID id, UUID pedidoId, String destinatario, int tentativas) {}

  private final JdbcTemplate jdbc;
  private final JavaMailSender mail;
  private final PdfIngresso pdf;
  private final QrAssinador qr;
  private final String remetente;
  private final int maxTentativas;
  private final long recuoMs;

  public NotificacaoWorker(
      JdbcTemplate jdbc,
      JavaMailSender mail,
      PdfIngresso pdf,
      QrAssinador qr,
      @Value("${emissao.notificacao.remetente:ingressos@pulsepass.test}") String remetente,
      @Value("${emissao.notificacao.max-tentativas:5}") int maxTentativas,
      @Value("${emissao.notificacao.recuo-ms:5000}") long recuoMs) {
    this.jdbc = jdbc;
    this.mail = mail;
    this.pdf = pdf;
    this.qr = qr;
    this.remetente = remetente;
    this.maxTentativas = maxTentativas;
    this.recuoMs = recuoMs;
  }

  @Scheduled(fixedDelayString = "${emissao.notificacao.poll-ms:2000}")
  @Transactional
  public void enviarPendentes() {
    List<Pendente> pendentes =
        jdbc.query(
            """
            select id, pedido_id, destinatario, tentativas from notificacao
            where status = 'PENDENTE' and proxima_tentativa <= now()
            order by criada_em limit 10 for update skip locked
            """,
            (rs, i) ->
                new Pendente(
                    rs.getObject("id", UUID.class),
                    rs.getObject("pedido_id", UUID.class),
                    rs.getString("destinatario"),
                    rs.getInt("tentativas")));
    pendentes.forEach(this::tentar);
  }

  private void tentar(Pendente p) {
    try {
      enviar(p);
      // o endereço some assim que o e-mail sai (minimização, RNF15)
      jdbc.update(
          "update notificacao set status = 'ENVIADA', enviada_em = now(), destinatario = null, tentativas = ?, erro = null where id = ?",
          p.tentativas() + 1,
          p.id());
    } catch (MessagingException | RuntimeException e) {
      falhar(p, e);
    }
  }

  private void enviar(Pendente p) throws MessagingException {
    List<Map<String, Object>> ingressos =
        jdbc.queryForList(
            "select id, evento_nome, descricao, emitido_em from ingresso where pedido_id = ? order by item_ordem, unidade",
            p.pedidoId());
    if (ingressos.isEmpty()) {
      throw new IllegalStateException("pedido sem ingressos");
    }
    String evento = String.valueOf(ingressos.get(0).get("evento_nome"));
    var mensagem = mail.createMimeMessage();
    var h = new MimeMessageHelper(mensagem, true, "UTF-8");
    h.setFrom(remetente);
    h.setTo(p.destinatario());
    h.setSubject("Seus ingressos PulsePass - " + evento);
    h.setText(
        "Seu pagamento foi confirmado.\n\n"
            + "Seguem "
            + ingressos.size()
            + " ingresso(s) em PDF para o evento "
            + evento
            + ". Apresente o QR Code na entrada; cada ingresso vale uma única entrada.\n");
    for (var ingresso : ingressos) {
      UUID id = (UUID) ingresso.get("id");
      h.addAttachment(
          "ingresso-" + id + ".pdf",
          new ByteArrayResource(pdf.gerar(ingresso, qr.gerar(id))),
          "application/pdf");
    }
    mail.send(mensagem);
  }

  /** Recua mais a cada tentativa; esgotadas, marca FALHA e apaga o endereço. */
  private void falhar(Pendente p, Exception e) {
    int tentativas = p.tentativas() + 1;
    // só o tipo do erro: a mensagem do SMTP costuma trazer o endereço do destinatário
    String erro = e.getClass().getSimpleName();
    if (tentativas >= maxTentativas) {
      log.warn("Notificação {} desistiu após {} tentativas ({})", p.id(), tentativas, erro);
      jdbc.update(
          "update notificacao set status = 'FALHA', tentativas = ?, erro = ?, destinatario = null where id = ?",
          tentativas,
          erro,
          p.id());
    } else {
      log.warn("Notificação {} falhou ({}); tentativa {}", p.id(), erro, tentativas);
      jdbc.update(
          "update notificacao set tentativas = ?, erro = ?, proxima_tentativa = now() + (? * interval '1 millisecond') where id = ?",
          tentativas,
          erro,
          recuoMs * tentativas,
          p.id());
    }
  }
}
