# E-mail do ingresso (comprovante multicanal)

Histórias: US25. Pacote 2: NotificacaoWorker / EmailSender, tabela NOTIFICACAO.

1. Ao emitir os ingressos de um pedido pago, a Emissão grava uma notificação PENDENTE na mesma transação (sem escrita dupla). O e-mail do comprador vem do JWT no momento do pagamento e viaja no evento `PedidoPago` (`clienteEmail`).
2. Um worker envia o e-mail com os PDFs anexados por SMTP (Mailpit no Compose). Em falha, tenta de novo até 5 vezes e marca FALHA; nunca envia duas vezes o mesmo pedido.
3. Depois do envio, ou de FALHA definitiva, o endereço é apagado da linha (LGPD, RNF15).
4. Se `PedidoPago` não trouxer e-mail, a notificação não é criada e a emissão segue normalmente.
5. O mesmo evento `IngressoEmitido` alimenta o segundo canal: a tela do cliente (WebSocket).
6. Falha do SMTP nunca impede a emissão do ingresso (RNF11).
