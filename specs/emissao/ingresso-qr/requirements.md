# Emissão de ingresso com QR e validação na portaria (US24, US26, US28, RNF05, RNF14)

## Critérios de aceite
- Dado `PedidoPago`, o serviço emite um ingresso por assento (ou por unidade de lote) com QR assinado e carimbo de tempo, e publica `IngressoEmitido`.
- Dado o mesmo `PedidoPago` entregue duas vezes, não há ingressos duplicados (RNF05).
- Dado um ingresso EMITIDO e nunca utilizado, quando o operador lê o QR, então a resposta é 200 "LIBERADO" com evento e descrição, e o ingresso vira UTILIZADO.
- Quando o mesmo QR é lido de novo, a resposta é 409 "JA_UTILIZADO" com o horário da primeira leitura (US29).
- Dado um QR alterado, a resposta é 422 "INVALIDO", sem consultar o banco por ingresso (RNF14).
- Dadas 50 leituras simultâneas do mesmo QR, exatamente 1 é LIBERADO.
- O cliente lista seus ingressos (US26) e baixa o PDF só dos próprios (404 para os de outros).

## Fora do escopo desta spec
E-mail (US25, R2), atualização em tempo real (US27, R2), notificações.
