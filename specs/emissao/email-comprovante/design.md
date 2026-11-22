# E-mail: desenho

- Checkout: o e-mail do token entra em `PedidoPago.clienteEmail` na transação que conclui o pagamento e não é gravado em tabela nenhuma do Checkout (só no Outbox, que é o evento). Limite registrado: o e-mail fica no evento (retenção de 7 dias no Kafka e linha do Outbox).
- Emissão: tabela `notificacao(id, pedido_id unique, destinatario, status, tentativas, criada_em, enviada_em, erro)` (V4).
- `NotificacaoWorker` (@Scheduled, `select ... for update skip locked`) usa `JavaMailSender`; o PDF vem de `PdfIngresso`, o mesmo do endpoint.
- Compose: `mailpit` (SMTP 1025, UI 8025). Em teste, o Mailpit sobe por Testcontainers e a API dele prova o envio.
