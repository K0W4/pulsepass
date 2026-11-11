#!/usr/bin/env bash
# RNF09: sobe 2 réplicas de cada serviço de negócio e repete o fluxo ponta a ponta sem erro funcional.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "1. subir 2 réplicas de inventário, checkout e emissão"
docker compose up -d --wait --wait-timeout 600 --no-recreate \
  --scale inventario-service=2 --scale checkout-service=2 --scale emissao-service=2 >/dev/null 2>&1
for s in inventario-service checkout-service emissao-service; do
  espero "$(docker compose ps --status running -q "$s" | wc -l | tr -d ' ')" 2 "$s com 2 réplicas no ar"
done

echo "2. grupos de consumidores dividem as partições entre as réplicas"
membros() { docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group "$1" --members 2>/dev/null | awk 'NF>0 && $1!="GROUP"' | wc -l | tr -d ' '; }
for g in checkout-inventario.reserva.v1 inventario-checkout.pedido.v1 emissao-checkout.pedido.v1; do
  grupo_com_2() { [ "$(membros "$1")" = 2 ]; }
  repetir "grupo $g com 2 membros (partições divididas)" grupo_com_2 "$g"
done

echo "3. fluxo ponta a ponta com réplicas"
"$(dirname "$0")/smoke.sh" >"$TMP/smoke.log" 2>&1 && ok "smoke completo passou com 2 réplicas de cada serviço" || { cat "$TMP/smoke.log" | tail -20 >&2; falha "smoke falhou com réplicas"; }

echo "4. todas as réplicas estão sendo monitoradas"
PROM=${PROMETHEUS_URL:-http://localhost:9090}
total_up() { curl -fsS -G "$PROM/api/v1/query" --data-urlencode 'query=count(up{job="servicos"} == 1)' | jq -r '.data.result[0].value[1]'; }
oito_instancias() { [ "$(total_up)" = 8 ]; }
repetir "Prometheus enxerga as 8 instâncias (2+2+2 réplicas, gateway e simulador)" oito_instancias
echo; echo "RÉPLICAS OK"
