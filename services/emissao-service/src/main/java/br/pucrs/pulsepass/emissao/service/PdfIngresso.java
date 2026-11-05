package br.pucrs.pulsepass.emissao.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

/** Gera o PDF do ingresso com QR Code e carimbo de tempo (US24). */
@Component
public class PdfIngresso {

  private static final DateTimeFormatter FORMATO =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss 'UTC'").withZone(ZoneId.of("UTC"));

  public byte[] gerar(Map<String, Object> ingresso, String codigoQr) {
    try (PDDocument doc = new PDDocument();
        var saida = new ByteArrayOutputStream()) {
      PDPage pagina = new PDPage(PDRectangle.A5);
      doc.addPage(pagina);
      BitMatrix matriz = new QRCodeWriter().encode(codigoQr, BarcodeFormat.QR_CODE, 260, 260);
      BufferedImage imagem = MatrixToImageWriter.toBufferedImage(matriz);
      PDImageXObject qr = LosslessFactory.createFromImage(doc, imagem);
      var negrito = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
      var normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      try (PDPageContentStream c = new PDPageContentStream(doc, pagina)) {
        float topo = pagina.getMediaBox().getHeight() - 60;
        texto(c, negrito, 20, 40, topo, "PulsePass - Ingresso");
        texto(c, negrito, 16, 40, topo - 40, limpo(ingresso.get("evento_nome")));
        texto(c, normal, 12, 40, topo - 64, limpo(ingresso.get("descricao")));
        texto(c, normal, 10, 40, topo - 88, "Ingresso: " + ingresso.get("id"));
        texto(
            c,
            normal,
            10,
            40,
            topo - 104,
            "Emitido em: "
                + FORMATO.format(((java.sql.Timestamp) ingresso.get("emitido_em")).toInstant()));
        c.drawImage(qr, 75, topo - 400, 260, 260);
        texto(
            c,
            normal,
            8,
            40,
            40,
            "Apresente o QR Code na entrada. Cada ingresso vale uma única entrada.");
      }
      doc.save(saida);
      return saida.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("Falha ao gerar o PDF do ingresso", e);
    }
  }

  private static void texto(
      PDPageContentStream c, PDType1Font fonte, float tamanho, float x, float y, String s)
      throws IOException {
    c.beginText();
    c.setFont(fonte, tamanho);
    c.newLineAtOffset(x, y);
    c.showText(s);
    c.endText();
  }

  /** As fontes padrão do PDF só cobrem Latin-1 sem controle. */
  private static String limpo(Object valor) {
    return String.valueOf(valor).replaceAll("[^\\x20-\\x7EÀ-ÿ]", "?");
  }
}
