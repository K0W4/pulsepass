# Tempo real: desenho

- Spring Boot + `spring-websocket` (handler de texto puro, sem STOMP). Consome `inventario.assento.v1`, `checkout.pedido.v1` e `emissao.ingresso.v1` com `auto.offset.reset=latest` e `group.id` único por instância.
- `Roteador` (domínio, puro): recebe tópico + envelope JSON e devolve `(canal, mensagem)`; testado sem Spring.
- `Canais`: mapa canal → sessões; remove sessões fechadas ao enviar.
- Handshake: `/realtime/eventos/{id}` sem autenticação; `/realtime/pedidos/{id}` valida o JWT do parâmetro `token` pelo JWKS do Keycloak e exige o papel `cliente`.
- Gateway: rota `/api/realtime/**` (StripPrefix=1), liberada na segurança do gateway (a validação é do serviço) e fora do rate limit. O Nginx da SPA repassa o upgrade.
