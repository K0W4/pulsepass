#!/usr/bin/env bash
# RNF11: a queda do emissao-service não impede a venda; os eventos ficam no Kafka e o ingresso
# é emitido quando o serviço volta.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "1. preparar evento com um assento"
ORG=$(token organizador); CLI=$(token cliente)
NOME_EV="Show de Resiliência $(date +%s)"
req "$ORG" POST /api/eventos "{\"nome\":\"$NOME_EV\",\"local\":\"Arena\",\"dataHora\":\"2027-04-01T20:00:00Z\"}"
EV=$(corpo | jq -r .id)
req "$ORG" POST "/api/eventos/$EV/lotes" '{"nome":"Pista","preco":100.00,"quantidade":10,"inicioVenda":"2026-01-01T00:00:00Z","fimVenda":"2027-03-01T00:00:00Z"}'
LOTE=$(corpo | jq -r .id)
req "$ORG" POST "/api/eventos/$EV/setores" "{\"nome\":\"Setor R\",\"marcado\":true,\"loteId\":\"$LOTE\",\"codigos\":[\"R1\"]}"
ASSENTO=$(curl -fsS "$GW/api/eventos/$EV/assentos" | jq -r '.[0].id')
req "$CLI" POST /api/reservas "{\"eventoId\":\"$EV\",\"assentoIds\":[\"$ASSENTO\"]}"
espero "$(status)" 201 "reserva criada"; RES=$(corpo | jq -r .reservaId)
pedido_reservado() { req "$CLI" POST /api/pedidos "{\"reservaId\":\"$RES\"}" && [ "$(corpo | jq -r .status)" = RESERVADO ]; }
repetir "pedido RESERVADO" pedido_reservado
PED=$(corpo | jq -r .id)

echo "2. derrubar o emissao-service e comprar"
docker compose stop emissao-service >/dev/null
req "$CLI" POST /api/ingressos
espero "$(status)" 503 "com a Emissão fora, o gateway responde 503 para ingressos" || true
req "$CLI" POST /api/pagamentos "{\"pedidoId\":\"$PED\"}" "Idempotency-Key: res-$PED"
espero "$(status)" 200 "pagamento aprovado com a Emissão fora do ar"
espero "$(corpo | jq -r .status)" PAGO "pedido PAGO"
assento_vendido() { curl -fsS "$GW/api/eventos/$EV/assentos" | jq -e '.[0].status=="VENDIDO"'; }
repetir "Inventário confirmou a venda sem depender da Emissão" assento_vendido

echo "3. a Emissão volta e processa o que ficou retido no Kafka"
docker compose start emissao-service >/dev/null
for _ in $(seq 1 60); do
  [ "$(docker compose ps --format '{{.Health}}' emissao-service)" = healthy ] && break; sleep 2
done
ingresso_emitido() { req "$CLI" GET /api/ingressos && [ "$(corpo | jq --arg n "$NOME_EV" 'map(select(.evento_nome==$n))|length')" = 1 ]; }
repetir "ingresso emitido depois que a Emissão voltou" ingresso_emitido
echo; echo "RESILIÊNCIA OK"
