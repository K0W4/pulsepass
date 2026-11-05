# Pedido e pagamento idempotente (US19, US20, US21, US23, RNF03, RNF10)

## Critérios de aceite
- Dado um pedido AGUARDANDO pagamento, quando o cliente envia `POST /pagamentos` duas vezes com a mesma `Idempotency-Key`, então o gateway é chamado apenas uma vez, a segunda resposta é idêntica à primeira e existe somente um registro de pagamento.
- Dada a mesma chave com outro pedido, então a resposta é 422.
- Dado `POST /pagamentos` sem `Idempotency-Key`, então a resposta é 400.
- Dado um gateway que recusa, então o pedido volta a RESERVADO (nova tentativa na mesma reserva, US23) e a resposta é 402.
- Dado um gateway fora do ar, após retentativas com backoff a resposta é 503, o pedido volta a RESERVADO e a mesma chave pode ser reutilizada.
- Dado `ReservaExpirada` ou `ReservaCancelada`, o pedido vira EXPIRADO e `PedidoExpirado` é publicado.
- Dado `POST /pedidos` antes de `ReservaCriada` chegar, o pedido fica CRIADO e avança para RESERVADO quando o evento chegar.
