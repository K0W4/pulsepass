#!/usr/bin/env bash
# Testes de carga (k6, em contêiner na rede do Compose). Grava o resumo em docs/evidencias/.
# Uso: scripts/carga.sh [catalogo|reserva|todos]
set -uo pipefail
. "$(dirname "$0")/lib.sh"
mkdir -p docs/evidencias
K6=grafana/k6:0.54.0
rodar() { # rodar NOME
  local nome=$1 saida=docs/evidencias/k6-$1.json
  docker run --rm -i --network pulsepass_default \
    -e GW=http://api-gateway:8080 -e KC=http://keycloak:8080 -e DEV_USER_PASSWORD="$DEV_USER_PASSWORD" \
    -e RATE="${RATE:-}" -e DURATION="${DURATION:-}" \
    -v "$PWD/scripts/k6:/scripts:ro" -v "$PWD/docs/evidencias:/out" \
    "$K6" run --summary-export="/out/k6-$nome.json" "/scripts/$nome.js" >"$TMP/$nome.log" 2>&1
  local codigo=$?
  jq 'del(.setup_data)' "$saida" >"$TMP/resumo.json" && mv "$TMP/resumo.json" "$saida" # tira os ids dos assentos do resumo
  grep -E 'http_req_duration|http_reqs|checks|iterations|✓|✗|THRESHOLDS|dropped' "$TMP/$nome.log" | head -14
  local met=http_req_duration falha=http_req_failed
  [ "$nome" = reserva ] && { met='http_req_duration{name:reserva}'; falha='http_req_failed{name:reserva}'; }
  printf '  %s: p95=%.1f ms, taxa=%.0f req/s, falhas=%.2f%%, thresholds %s\n' "$nome" \
    "$(jq --arg m "$met" '.metrics[$m]["p(95)"]' "$saida")" "$(jq '.metrics.http_reqs.rate' "$saida")" \
    "$(jq --arg m "$falha" '(.metrics[$m].value // 0)*100' "$saida")" "$([ $codigo -eq 0 ] && echo ATENDIDOS || echo NÃO-ATENDIDOS)"
  return $codigo
}
alvo=${1:-todos}; rc=0
[ "$alvo" = catalogo ] || [ "$alvo" = todos ] && { echo "RNF06: catálogo, 1.000 req/s por 30 s"; rodar catalogo || rc=1; }
[ "$alvo" = reserva ] || [ "$alvo" = todos ] && { echo "RNF07: reserva, 200 req/s por 30 s"; rodar reserva || rc=1; }
exit $rc
