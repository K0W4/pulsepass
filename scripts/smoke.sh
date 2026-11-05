#!/usr/bin/env bash
# Fluxo ponta a ponta contra o Compose no ar: organizador cria evento, cliente reserva e paga
# (duas vezes, mesma Idempotency-Key), recebe o ingresso e o operador valida o QR duas vezes.
# Qualquer asserção que não bater encerra o script com erro.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
if [ -f .env ]; then . ./.env; else . ./.env.example; fi
set +a

GW=${GATEWAY_URL:-http://localhost:8080}
KC=${KEYCLOAK_URL:-http://localhost:8081}
SENHA=${DEV_USER_PASSWORD:?defina DEV_USER_PASSWORD}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

ok()    { printf '  ok    %s\n' "$1"; }
falha() { printf '  FALHA %s\n' "$1" >&2; exit 1; }
espero() { # espero VALOR_OBTIDO VALOR_ESPERADO descricao
  if [ "$1" = "$2" ]; then ok "$3"; else falha "$3 (esperado '$2', obtido '$1')"; fi
}

token() {
  curl -fsS "$KC/realms/pulsepass/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=pulsepass-api \
    -d "username=$1@pulsepass.test" --data-urlencode "password=$SENHA" | jq -r .access_token
}

# req TOKEN METODO CAMINHO [CORPO] [CABECALHO]  ->  corpo em $TMP/corpo, status em $TMP/status
req() {
  local t=$1 m=$2 c=$3 b=${4:-} h=${5:-}
  local args=(-sS -o "$TMP/corpo" -w '%{http_code}' -X "$m" "$GW$c")
  [ -n "$t" ] && args+=(-H "Authorization: Bearer $t")
  [ -n "$b" ] && args+=(-H 'Content-Type: application/json' -d "$b")
  [ -n "$h" ] && args+=(-H "$h")
  curl "${args[@]}" >"$TMP/status"
}
status() { cat "$TMP/status"; }
corpo()  { cat "$TMP/corpo"; }

# repetir ATÉ_60s: executa o comando até ele devolver 0
repetir() {
  local d=$1; shift
  for _ in $(seq 1 60); do
    if "$@" >/dev/null 2>&1; then ok "$d"; return 0; fi
    sleep 1
  done
  falha "$d (tempo esgotado)"
}

echo "1. tokens do Keycloak"
ORG=$(token organizador); CLI=$(token cliente); OPE=$(token operador)
for t in "$ORG" "$CLI" "$OPE"; do [ -n "$t" ] && [ "$t" != null ] || falha "token vazio"; done
ok "tokens emitidos para organizador, cliente e operador"

echo "2. organizador cadastra evento, lote e setor com 2 assentos"
req "$ORG" POST /api/eventos '{"nome":"Show de Smoke","local":"Arena","dataHora":"2027-03-01T20:00:00Z"}'
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
ingresso_emitido() { req "$CLI" GET /api/ingressos && [ "$(corpo | jq 'length')" = 1 ]; }
repetir "ingresso emitido após PedidoPago" ingresso_emitido
CODIGO=$(corpo | jq -r '.[0].codigo'); ID=$(corpo | jq -r '.[0].id')
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

echo
echo "SMOKE OK"
