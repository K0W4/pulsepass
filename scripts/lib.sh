#!/usr/bin/env bash
# Funções comuns dos scripts de verificação (smoke, réplicas, resiliência).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
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

