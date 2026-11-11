# Evidências de execução

Medições feitas em 05/11/2026 num laptop (Docker Desktop, um contêiner por serviço, **sem latência de
rede real**). Servem para verificar as metas dos requisitos, não como benchmark de produção.

| Requisito | Meta | Resultado | Arquivo |
|---|---|---|---|
| RNF06 catálogo | p95 <= 200 ms a 1.000 req/s por 30 s | p95 = 71 ms, 997 req/s, 0% de falhas | `k6-catalogo.json` |
| RNF07 reserva | p95 <= 500 ms a 200 req/s por 30 s | p95 = 7.4 ms, 195 req/s, 6.000 reservas criadas, 0% de falhas | `k6-reserva.json` |
| RNF22 acessibilidade | Lighthouse/axe >= 90 | Lighthouse 100 (catálogo e detalhe, celular e desktop); axe-core sem violações WCAG A/AA em todas as telas | `lighthouse-resumo.json` |

Reproduzir: `docker compose up -d --build --wait && ./scripts/carga.sh`. Os scripts `smoke.sh`,
`replicas.sh` e `resiliencia.sh` cobrem o fluxo completo, as 2 réplicas (RNF09) e a queda da Emissão
(RNF11). O axe-core foi executado no navegador contra cada tela, com os papéis de cliente,
organizador e operador (não faz parte do repositório).
