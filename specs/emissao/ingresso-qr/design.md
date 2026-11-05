# Design

- Consumidor de `checkout.pedido.v1` (`PedidoPago`), deduplicado por `eventId`; também protegido por `unique (pedido_id, item_ordem, unidade)`.
- QR: `PP1.<ingressoId>.<assinatura>`, assinatura = HMAC-SHA256 do id com segredo do serviço (`EMISSAO_QR_SECRET`), em base64url.
- `POST /validacoes {codigo}` (OPERADOR): verifica a assinatura, depois `UPDATE ingresso SET status='UTILIZADO' ... WHERE id=? AND status='EMITIDO'`; a contagem de linhas decide LIBERADO ou JA_UTILIZADO. Toda leitura grava `validacao_acesso`.
- `GET /ingressos` e `GET /ingressos/{id}/pdf` (CLIENTE, só do dono). O PDF é gerado sob demanda (PDFBox + ZXing) a partir dos dados do ingresso: o serviço continua sem estado (RNF09).
- Eventos em `emissao.ingresso.v1` (chave `pedidoId`): IngressoEmitido e IngressoUtilizado.
