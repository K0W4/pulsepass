#!/bin/sh
# Gera o config.js da SPA a partir do ambiente do contêiner.
cat > /usr/share/nginx/html/config.js <<EOF
window.__CONFIG__ = { keycloakUrl: '${KEYCLOAK_URL:-http://localhost:8081}', realm: '${KEYCLOAK_REALM:-pulsepass}', clientId: '${KEYCLOAK_CLIENT:-pulsepass-api}' };
EOF
