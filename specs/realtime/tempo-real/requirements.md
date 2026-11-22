# Tempo real (realtime-service): requisitos

Histórias: US14 (mapa de assentos ao vivo), US27 (status do pedido sem recarregar), US32 (painel do organizador ao vivo). Pacote 1, módulo "Emissão e Notificações" (atualização de tela em tempo real).

1. O cliente abre um WebSocket por evento (`/api/realtime/eventos/{id}`) e recebe cada mudança de assento (`AssentoReservado`, `AssentoLiberado`, `AssentoVendido`) em até 1 s. O canal é público, como o catálogo.
2. O cliente abre um WebSocket por pedido (`/api/realtime/pedidos/{id}?token=<jwt>`) e recebe `PedidoAguardandoPagamento`, `PedidoPago`, `PagamentoRecusado`, `PedidoExpirado` e `IngressoEmitido`. Exige JWT válido com o papel `cliente`.
3. O serviço não guarda estado: com várias réplicas, cada uma recebe todos os eventos (um grupo Kafka por instância) e entrega aos clientes conectados a ela.
4. Se o WebSocket cair, a SPA volta ao polling de 5 s (degradação sem perder a função).
5. O serviço não escreve em banco e não publica eventos; só consome.

[ABERTO] resolvido: o canal do pedido não confere o dono do pedido (o id é um UUID não adivinhável e a mensagem não traz dado pessoal); limite registrado no Pacote 4.
