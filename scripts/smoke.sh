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

echo
echo "SMOKE OK"
