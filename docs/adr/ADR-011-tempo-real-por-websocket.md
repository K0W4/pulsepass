# ADR-011: tempo real por WebSocket, consumidor por instância

**Estado:** aceita (Pacote 4). **Contexto:** o mapa de assentos e o status do pedido eram atualizados por *polling* de 5 s.

**Decisão.** O `realtime-service` consome `inventario.assento.v1`, `checkout.pedido.v1` e `emissao.ingresso.v1` e entrega a cada navegador, por WebSocket, só os eventos do canal assinado (`eventos/{id}` ou `pedidos/{id}`). Cada réplica usa um **grupo Kafka próprio** (UUID por instância) e lê a partir do fim do tópico, de modo que todas recebem todos os eventos e entregam aos clientes conectados a elas, sem estado compartilhado.

**Autenticação.** O canal do evento é público (o catálogo também é). O canal do pedido exige JWT com o papel `cliente`, enviado em `?token=` porque o navegador não põe cabeçalho no WebSocket; o serviço valida pelo JWKS. As mensagens não carregam dado pessoal (só ids e o tipo do evento).

**Degradação.** Se o socket cair, a SPA reconecta com recuo exponencial e volta ao *polling* de 5 s; com o socket aberto, o *polling* passa a 30 s como rede de segurança.

**Limites.** O token na URL pode aparecer em logs de acesso (evolução: ticket de uso único). O canal do pedido não confere o dono do pedido; o id é um UUID não adivinhável e a mensagem não tem dado pessoal. Quem conecta depois não recebe o passado: a tela consulta a API ao abrir.
