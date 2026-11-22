#!/usr/bin/env bash
# Fluxo ponta a ponta contra o Compose no ar: organizador cria evento, cliente reserva e paga
# (duas vezes, mesma Idempotency-Key), recebe o ingresso e o operador valida o QR duas vezes.
# Qualquer asserção que não bater encerra o script com erro.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "0. auto-cadastro de cliente pelo Keycloak (US01)"
NOVO="novo-$(date +%s)@pulsepass.test"
JAR="$TMP/cookies"
PAGINA=$(curl -fsS -c "$JAR" -b "$JAR" "$KC/realms/pulsepass/protocol/openid-connect/registrations?client_id=pulsepass-api&response_type=code&scope=openid&redirect_uri=http://localhost:3000/")
ACAO=$(printf '%s' "$PAGINA" | grep -o 'action="[^"]*"' | head -1 | sed 's/^action="//; s/"$//; s/&amp;/\&/g')
[ -n "$ACAO" ] || falha "formulário de cadastro não encontrado"
COD=$(curl -sS -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" "$ACAO" \
  --data-urlencode "email=$NOVO" --data-urlencode "firstName=Novo" --data-urlencode "lastName=Cliente" \
  --data-urlencode "password=$SENHA" --data-urlencode "password-confirm=$SENHA")
espero "$COD" 302 "cadastro aceito pelo Keycloak"
NOVO_TOKEN=$(curl -fsS "$KC/realms/pulsepass/protocol/openid-connect/token" -d grant_type=password -d client_id=pulsepass-api \
  --data-urlencode "username=$NOVO" --data-urlencode "password=$SENHA" | jq -r .access_token)
espero "$(printf '%s' "$NOVO_TOKEN" | jq -R 'split(".")[1] | gsub("-";"+") | gsub("_";"/") | @base64d | fromjson | .realm_access.roles | index("cliente") != null')" true "quem se cadastra recebe o papel cliente"
req "$NOVO_TOKEN" GET /api/ingressos
espero "$(status)" 200 "o novo cliente já acessa a área de cliente"

echo "1. tokens do Keycloak"
ORG=$(token organizador); CLI=$(token cliente); OPE=$(token operador)
for t in "$ORG" "$CLI" "$OPE"; do [ -n "$t" ] && [ "$t" != null ] || falha "token vazio"; done
ok "tokens emitidos para organizador, cliente e operador"

echo "2. organizador cadastra evento, lote e setor com 2 assentos"
NOME_EV="Show de Smoke $(date +%s)"
req "$ORG" POST /api/eventos "{\"nome\":\"$NOME_EV\",\"local\":\"Arena\",\"dataHora\":\"2027-03-01T20:00:00Z\"}"
espero "$(status)" 201 "criar evento"; EV=$(corpo | jq -r .id)
req "$ORG" POST "/api/eventos/$EV/lotes" '{"nome":"Pista","preco":250.00,"quantidade":10,"inicioVenda":"2026-01-01T00:00:00Z","fimVenda":"2027-02-01T00:00:00Z"}'
espero "$(status)" 201 "criar lote"; LOTE=$(corpo | jq -r .id)
req "$ORG" POST "/api/eventos/$EV/setores" "{\"nome\":\"Setor A\",\"marcado\":true,\"loteId\":\"$LOTE\",\"codigos\":[\"A1\",\"A2\"]}"
espero "$(status)" 201 "criar setor com assentos"

echo "3. catálogo público e RBAC no gateway e nos serviços"
curl -fsS "$GW/api/eventos" | jq -e --arg id "$EV" 'map(select(.id==$id))|length==1' >/dev/null || falha "evento ausente do catálogo"
ok "evento aparece no catálogo sem token"
req "$CLI" POST /api/eventos '{"nome":"x","local":"y","dataHora":"2027-01-01T00:00:00Z"}'
espero "$(status)" 403 "cliente não cria evento"
req "" POST /api/reservas '{}'
espero "$(status)" 401 "sem token a reserva é negada"

echo "4. cliente reserva o assento A1"
ASSENTO=$(curl -fsS "$GW/api/eventos/$EV/assentos" | jq -r '.[]|select(.codigo=="A1")|.id')
req "$CLI" POST /api/reservas "{\"eventoId\":\"$EV\",\"assentoIds\":[\"$ASSENTO\"]}"
espero "$(status)" 201 "reservar A1"; RES=$(corpo | jq -r .reservaId)
req "$CLI" POST /api/reservas "{\"eventoId\":\"$EV\",\"assentoIds\":[\"$ASSENTO\"]}"
espero "$(status)" 409 "segunda reserva do mesmo assento recebe 409"
req "$ORG" POST /api/reservas "{\"eventoId\":\"$EV\",\"assentoIds\":[\"$ASSENTO\"]}"
espero "$(status)" 403 "organizador não reserva"

echo "5. pedido e pagamento idempotente"
pedido_reservado() {
  req "$CLI" POST /api/pedidos "{\"reservaId\":\"$RES\"}" && [ "$(corpo | jq -r .status)" = RESERVADO ]
}
repetir "ReservaCriada chegou ao Checkout e o pedido ficou RESERVADO" pedido_reservado
PED=$(corpo | jq -r .id)
req "$CLI" POST /api/pagamentos "{\"pedidoId\":\"$PED\"}" "Idempotency-Key: smoke-$PED"
espero "$(status)" 200 "primeiro pagamento"; P1=$(corpo)
req "$CLI" POST /api/pagamentos "{\"pedidoId\":\"$PED\"}" "Idempotency-Key: smoke-$PED"
espero "$(status)" 200 "segundo pagamento com a mesma chave"; P2=$(corpo)
espero "$P2" "$P1" "resposta idêntica à primeira"
espero "$(echo "$P1" | jq -r .status)" PAGO "pedido PAGO"
req "$CLI" POST /api/pagamentos "{\"pedidoId\":\"$PED\"}"
espero "$(status)" 400 "pagamento sem Idempotency-Key"

echo "6. ingresso emitido, PDF e validação na portaria"
ingresso_emitido() { req "$CLI" GET /api/ingressos && [ "$(corpo | jq --arg n "$NOME_EV" 'map(select(.evento_nome==$n))|length')" = 1 ]; }
repetir "ingresso emitido após PedidoPago" ingresso_emitido
CODIGO=$(corpo | jq -r --arg n "$NOME_EV" 'map(select(.evento_nome==$n))[0].codigo'); ID=$(corpo | jq -r --arg n "$NOME_EV" 'map(select(.evento_nome==$n))[0].id')
curl -fsS "$GW/api/ingressos/$ID/pdf" -H "Authorization: Bearer $CLI" -o "$TMP/ingresso.pdf"
espero "$(head -c 5 "$TMP/ingresso.pdf")" "%PDF-" "PDF do ingresso"
req "$OPE" POST /api/validacoes "{\"codigo\":\"$CODIGO\"}"
espero "$(status)" 200 "primeira leitura"; espero "$(corpo | jq -r .resultado)" LIBERADO "resultado LIBERADO"
req "$OPE" POST /api/validacoes "{\"codigo\":\"$CODIGO\"}"
espero "$(status)" 409 "segunda leitura"; espero "$(corpo | jq -r .resultado)" JA_UTILIZADO "resultado JA_UTILIZADO"
espero "$(corpo | jq -r '.primeiraLeitura != null')" true "informa o horário da primeira leitura"
req "$OPE" POST /api/validacoes '{"codigo":"PP1.00000000-0000-0000-0000-000000000000.forjado"}'
espero "$(status)" 422 "QR forjado é INVALIDO"
req "$CLI" POST /api/validacoes "{\"codigo\":\"$CODIGO\"}"
espero "$(status)" 403 "cliente não valida ingressos"

echo "7. venda refletida no catálogo"
assento_vendido() { curl -fsS "$GW/api/eventos/$EV/assentos" | jq -e '.[]|select(.codigo=="A1")|.status=="VENDIDO"'; }
repetir "AssentoVendido refletido no catálogo" assento_vendido

echo "8. observabilidade: métricas, trace único da compra e dashboard (RNF16)"
PROM=${PROMETHEUS_URL:-http://localhost:9090}
JAEGER=${JAEGER_URL:-http://localhost:16686}
GRAFANA=${GRAFANA_URL:-http://localhost:3001}
metrica_presente() {
  curl -fsS -G "$PROM/api/v1/query" --data-urlencode "query=http_server_requests_seconds_count{application=\"checkout-service\"}" \
    | jq -e '.data.result | length > 0'
}
repetir "Prometheus coleta métricas HTTP do checkout-service" metrica_presente
# um único trace deve conter spans dos quatro serviços (o trace atravessa o Outbox e o Kafka)
trace_da_compra() {
  curl -fsS "$JAEGER/api/traces?service=api-gateway&lookback=1h&limit=100" | jq -e '
    [.data[] | select(([.processes[].serviceName] | unique) as $s
      | (["api-gateway","checkout-service","emissao-service","inventario-service"] - $s | length) == 0)] | length > 0'
}
repetir "um único trace atravessa gateway, checkout, emissão e inventário" trace_da_compra
curl -fsS "$GRAFANA/api/search?query=PulsePass" | jq -e 'length >= 1' >/dev/null || falha "dashboard do Grafana não provisionado"
ok "dashboard PulsePass provisionado no Grafana"
curl -fsS "$GRAFANA/api/dashboards/uid/pulsepass" | jq -e '.dashboard.panels | length >= 10' >/dev/null || falha "painéis de telemetria e tempo real ausentes do Grafana"
ok "dashboard com os painéis de telemetria e de tempo real"

# ---------------------------------------------------------------- Pacote 4
MAILPIT=${MAILPIT_URL:-http://localhost:8025}
SPA=${SPA_URL:-http://localhost:3000}
WS=${WS_URL:-ws://localhost:8080}
ADM=$(token admin)
[ -n "$ADM" ] && [ "$ADM" != null ] || falha "token do admin vazio"

# ouvir VAR ARQUIVO URL TEXTO: abre um WebSocket em segundo plano e só volta quando ele conectou
ouvir() {
  node scripts/ws-espera.mjs "$3" "$4" 40 >"$2" 2>&1 &
  printf -v "$1" '%s' "$!"
  for _ in $(seq 1 60); do grep -q conectado "$2" 2>/dev/null && return 0; sleep 0.2; done
  falha "WebSocket não conectou em $3"
}
esperar_ouvinte() { if wait "$1"; then ok "$2"; else falha "$2 (nada recebido)"; fi; }

echo "9. renovação de sessão pelo refresh token (US03, RNF12)"
SESSAO=$(curl -fsS "$KC/realms/pulsepass/protocol/openid-connect/token" -d grant_type=password -d client_id=pulsepass-api \
  --data-urlencode "username=$NOVO" --data-urlencode "password=$SENHA")
AT=$(printf '%s' "$SESSAO" | jq -r .access_token); RT=$(printf '%s' "$SESSAO" | jq -r .refresh_token)
espero "$(printf '%s' "$SESSAO" | jq -r .expires_in)" 900 "access token de 15 minutos"
espero "$(printf '%s' "$SESSAO" | jq -r '.refresh_expires_in >= 600000')" true "refresh token de 7 dias"
RENOVADA=$(curl -fsS "$KC/realms/pulsepass/protocol/openid-connect/token" -d grant_type=refresh_token -d client_id=pulsepass-api -d "refresh_token=$RT")
AT2=$(printf '%s' "$RENOVADA" | jq -r .access_token)
[ -n "$AT2" ] && [ "$AT2" != null ] && [ "$AT2" != "$AT" ] || falha "o refresh não devolveu um token novo"
ok "o refresh token devolve um access token novo"
req "$AT2" GET /api/ingressos
espero "$(status)" 200 "o token renovado é aceito pelo gateway"
NOVO_TOKEN=$AT2

echo "10. busca no catálogo e publicação de eventos (US13, US10)"
busca() { curl -fsS -G "$GW/api/eventos" "$@" | jq -r --arg id "$EV" 'map(select(.id==$id))|length'; }
espero "$(busca --data-urlencode q=smoke)" 1 "busca por nome (sem maiúsculas)"
espero "$(busca --data-urlencode q=smoke --data-urlencode local=ARENA)" 1 "busca por nome e local"
espero "$(busca --data-urlencode local=curitiba)" 0 "busca por outro local não traz o evento"
espero "$(busca --data-urlencode de=2027-03-01 --data-urlencode ate=2027-03-01)" 1 "busca pelo dia do evento"
espero "$(busca --data-urlencode ate=2027-02-28)" 0 "busca anterior à data não traz o evento"
espero "$(curl -sS -o /dev/null -w '%{http_code}' "$GW/api/eventos?de=amanha")" 400 "data inválida é recusada"
req "$CLI" POST "/api/eventos/$EV/despublicar"
espero "$(status)" 403 "cliente não despublica"
req "$ORG" POST "/api/eventos/$EV/despublicar"
espero "$(status)" 204 "organizador despublica"
espero "$(busca)" 0 "evento despublicado sai do catálogo"
espero "$(curl -sS -o /dev/null -w '%{http_code}' "$GW/api/eventos/$EV")" 404 "evento despublicado dá 404"
req "$ORG" GET /api/eventos/meus
espero "$(corpo | jq -r --arg id "$EV" '.[]|select(.id==$id)|.status')" RASCUNHO "o organizador ainda vê o rascunho em 'meus eventos'"
req "$ORG" POST "/api/eventos/$EV/publicar"
espero "$(status)" 204 "organizador publica de novo"
espero "$(busca)" 1 "evento publicado volta ao catálogo"

echo "11. fila virtual, tempo real e e-mail do ingresso (US18, US14, US27, US25)"
req "$ORG" PUT "/api/eventos/$EV/fila-ativa" '{"ativa":true}'
espero "$(status)" 204 "organizador liga a fila virtual"
ASSENTO2=$(curl -fsS "$GW/api/eventos/$EV/assentos" | jq -r '.[]|select(.codigo=="A2")|.id')
req "$NOVO_TOKEN" POST /api/reservas "{\"eventoId\":\"$EV\",\"assentoIds\":[\"$ASSENTO2\"]}"
espero "$(status)" 403 "sem admissão, a reserva é negada"
espero "$(corpo | jq -r .motivo)" FILA_VIRTUAL "motivo FILA_VIRTUAL"
req "$NOVO_TOKEN" POST "/api/eventos/$EV/fila"
espero "$(status)" 200 "cliente entra na fila"
admitido() { req "$NOVO_TOKEN" GET "/api/eventos/$EV/fila" && [ "$(corpo | jq -r .status)" = ADMITIDO ]; }
repetir "a fila admite o cliente" admitido
ouvir PID_ASSENTO "$TMP/ws-assento" "$WS/api/realtime/eventos/$EV" AssentoReservado
req "$NOVO_TOKEN" POST /api/reservas "{\"eventoId\":\"$EV\",\"assentoIds\":[\"$ASSENTO2\"]}"
espero "$(status)" 201 "com a admissão, a reserva passa"; RES2=$(corpo | jq -r .reservaId)
esperar_ouvinte "$PID_ASSENTO" "tempo real: o WebSocket do evento recebeu AssentoReservado"
req "$NOVO_TOKEN" GET "/api/eventos/$EV/fila"
espero "$(corpo | jq -r .status)" FORA "a admissão vale uma reserva"
req "$ORG" PUT "/api/eventos/$EV/fila-ativa" '{"ativa":false}'
espero "$(status)" 204 "organizador desliga a fila"
pedido2_reservado() { req "$NOVO_TOKEN" POST /api/pedidos "{\"reservaId\":\"$RES2\"}" && [ "$(corpo | jq -r .status)" = RESERVADO ]; }
repetir "pedido do cliente novo RESERVADO" pedido2_reservado
PED2=$(corpo | jq -r .id)
# o gateway aceita o handshake e só então consulta o serviço: quem não pode vê a conexão ser fechada
node scripts/ws-espera.mjs "$WS/api/realtime/pedidos/$PED2" @fechado 8 >/dev/null 2>&1 || falha "o canal do pedido ficou aberto sem token"
ok "canal do pedido é fechado para quem não tem token"
node scripts/ws-espera.mjs "$WS/api/realtime/pedidos/$PED2?token=$(token operador)" @fechado 8 >/dev/null 2>&1 || falha "o canal do pedido ficou aberto para o papel operador"
ok "canal do pedido é fechado para o papel operador"
# direto no serviço (fora do gateway) a recusa é um 401 no handshake
upgrade() { docker compose exec -T realtime-service curl -s -m 2 -o /dev/null -w '%{http_code}' -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' -H "Sec-WebSocket-Key: $(openssl rand -base64 16)" "http://localhost:8080/realtime/pedidos/$PED2$1" || true; }
espero "$(upgrade '')" 401 "realtime-service: handshake do pedido sem token dá 401"
espero "$(upgrade '?token=lixo')" 401 "realtime-service: token inválido dá 401"
espero "$(upgrade "?token=$NOVO_TOKEN")" 101 "realtime-service: token de cliente é aceito (101)"
ouvir PID_PEDIDO "$TMP/ws-pedido" "$WS/api/realtime/pedidos/$PED2?token=$NOVO_TOKEN" IngressoEmitido
ouvir PID_VENDIDO "$TMP/ws-vendido" "$WS/api/realtime/eventos/$EV" AssentoVendido
req "$NOVO_TOKEN" POST /api/pagamentos "{\"pedidoId\":\"$PED2\"}" "Idempotency-Key: smoke2-$PED2"
espero "$(status)" 200 "cliente novo paga"
esperar_ouvinte "$PID_PEDIDO" "tempo real: o canal do pedido recebeu IngressoEmitido"
esperar_ouvinte "$PID_VENDIDO" "tempo real: o canal do evento recebeu AssentoVendido"
# o WebSocket também atravessa o Nginx da SPA (porta 3000)
node scripts/ws-espera.mjs "${SPA/http/ws}/api/realtime/eventos/$EV" @conectar 10 >/dev/null 2>&1 || falha "WebSocket não atravessa o Nginx"
ok "o WebSocket atravessa o Nginx da SPA"
email_chegou() {
  curl -fsS -G "$MAILPIT/api/v1/search" --data-urlencode "query=to:$NOVO" \
    | jq -e '.messages as $m | ($m|length)==1 and $m[0].Attachments==1 and ($m[0].Subject|startswith("Seus ingressos PulsePass"))'
}
repetir "e-mail com o ingresso em PDF chegou ao Mailpit" email_chegou
espero "$(curl -fsS "$SPA/" -o /dev/null -w '%{http_code}')" 200 "SPA responde na porta 3000"

echo "12. telemetria e painel analítico (US32, US33, RNF08)"
espero "$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$GW/api/telemetria/navegacao" -H 'Content-Type: application/json' -d '{"pagina":"catalogo"}')" 202 "navegação anônima aceita"
req "$CLI" GET "/api/telemetria/resumo?minutos=15"
espero "$(status)" 403 "cliente não vê o painel"
req "$ORG" GET /api/telemetria/operacional
espero "$(status)" 403 "organizador não vê as métricas operacionais"
resumo_ok() {
  req "$ORG" GET "/api/telemetria/resumo?minutos=15" && [ "$(status)" = 200 ] \
    && corpo | jq -e '.totais.pedidos_pagos >= 2 and .totais.ingressos_emitidos >= 2 and .totais.reservas_criadas >= 2 and (.totais.paginas_vistas // 0) >= 1'
}
repetir "o painel agrega reservas, pagamentos, ingressos e navegação" resumo_ok
emissao_ok() { req "$ADM" GET "/api/telemetria/emissao?minutos=60" && corpo | jq -e '.amostras >= 2 and .atendeMeta == true'; }
repetir "RNF08: o p95 da emissão está dentro de 30 s" emissao_ok
ok "p95 medido = $(corpo | jq -r '(.p95 * 100 | round) / 100') s com $(corpo | jq -r '.amostras') pedidos"
operacional_ok() { req "$ADM" GET /api/telemetria/operacional && corpo | jq -e '.disponivel == true and (.latenciaP95Segundos | length) > 0 and (.lagKafka | length) > 0'; }
repetir "US33: latência, erros e lag do Kafka lidos do Prometheus" operacional_ok

echo "13. IAM: organizador com aprovação do admin e auditoria (US05, US06)"
req "$NOVO_TOKEN" GET /api/iam/minha-solicitacao
espero "$(status)" 404 "antes do pedido não há solicitação"
req "$NOVO_TOKEN" POST /api/iam/organizadores/solicitacoes '{"razaoSocial":"Produtora Smoke","cnpj":"11.222.333/0001-82","contato":"smoke@produtora.test"}'
espero "$(status)" 400 "CNPJ com dígito errado é recusado"
req "$NOVO_TOKEN" POST /api/iam/organizadores/solicitacoes '{"razaoSocial":"Produtora Smoke","cnpj":"11.222.333/0001-81","contato":"smoke@produtora.test"}'
espero "$(status)" 201 "cliente pede para ser organizador"; SOL=$(corpo | jq -r .id)
req "$NOVO_TOKEN" POST /api/iam/organizadores/solicitacoes '{"razaoSocial":"Produtora Smoke","cnpj":"11.222.333/0001-81","contato":"smoke@produtora.test"}'
espero "$(status)" 409 "segunda solicitação pendente é recusada"
req "$NOVO_TOKEN" POST /api/eventos '{"nome":"antes da aprovação","local":"x","dataHora":"2027-01-01T00:00:00Z"}'
espero "$(status)" 403 "ainda não é organizador"
req "$CLI" POST "/api/iam/organizadores/solicitacoes/$SOL/aprovar"
espero "$(status)" 403 "cliente não aprova"
req "$ORG" GET /api/iam/auditoria
espero "$(status)" 403 "organizador não vê a auditoria"
req "$ADM" GET "/api/iam/organizadores/solicitacoes?status=PENDENTE"
espero "$(corpo | jq -r --arg id "$SOL" 'map(select(.id==$id))|length')" 1 "admin vê a solicitação pendente"
req "$ADM" POST "/api/iam/organizadores/solicitacoes/$SOL/aprovar"
espero "$(status)" 204 "admin aprova (o iam-service atribui o papel no Keycloak real)"
req "$ADM" POST "/api/iam/organizadores/solicitacoes/$SOL/aprovar"
espero "$(status)" 409 "decidir duas vezes dá 409"
RENOVADA=$(curl -fsS "$KC/realms/pulsepass/protocol/openid-connect/token" -d grant_type=refresh_token -d client_id=pulsepass-api -d "refresh_token=$RT")
NOVO_TOKEN=$(printf '%s' "$RENOVADA" | jq -r .access_token)
espero "$(printf '%s' "$NOVO_TOKEN" | jq -R 'split(".")[1] | gsub("-";"+") | gsub("_";"/") | @base64d | fromjson | .realm_access.roles | index("organizador") != null')" true "o token renovado traz o papel organizador"
req "$NOVO_TOKEN" POST /api/eventos '{"nome":"depois da aprovação","local":"x","dataHora":"2027-01-01T00:00:00Z"}'
espero "$(status)" 201 "o novo organizador cria evento"
curl -sS -o /dev/null "$KC/realms/pulsepass/protocol/openid-connect/token" -d grant_type=password -d client_id=pulsepass-api \
  --data-urlencode "username=$NOVO" --data-urlencode "password=errada-de-proposito"
auditoria_ok() {
  req "$ADM" GET "/api/iam/auditoria?limite=500" \
    && corpo | jq -e '([.[]|select(.origem=="KEYCLOAK" and .tipo=="LOGIN")]|length) > 0
        and ([.[]|select(.origem=="KEYCLOAK" and .tipo=="LOGIN_ERROR")]|length) > 0
        and ([.[]|select(.tipo=="ORGANIZADOR_APROVADO")]|length) > 0'
}
repetir "auditoria reúne logins, falha de login e a aprovação" auditoria_ok

echo "14. rate limit no gateway"
# uma rajada de verdade (um único curl, 150 conexões em paralelo): o balde tem 200 fichas e reabastece
# 100 por segundo, então 600 requisições em menos de 1 s deixam pelo menos ~300 sem ficha
CODIGOS=$(curl -s --parallel --parallel-max 150 -o /dev/null -w '%{http_code}\n' "$GW/api/eventos?i=[1-600]")
N429=$(printf '%s\n' "$CODIGOS" | grep -c '^429$' || true)
[ "$N429" -ge 1 ] || falha "nenhuma das 600 requisições simultâneas recebeu 429"
ok "rate limit: $N429 de 600 requisições anônimas simultâneas receberam 429"
sleep 4 # o balde se reabastece
espero "$(curl -sS -o /dev/null -w '%{http_code}' "$GW/api/eventos")" 200 "depois da pausa o gateway volta a responder"

echo
echo "SMOKE OK"
