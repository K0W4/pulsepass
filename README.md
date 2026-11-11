# PulsePass

Plataforma distribuída de bilhetagem, reserva e acesso para megaeventos. Projeto integrador de
Construção de Software (PUCRS, 2026/2). Este repositório é o **Pacote 3 (checkpoint)**: a fatia
vertical **R1**, do catálogo ao ingresso validado na portaria, com SPA, observabilidade, testes de
carga, réplicas e teste de resiliência.

Integrantes: Gabriel Kowaleski e Mayra Bordin.

## O que já funciona (R1)

| Serviço | Responsabilidade | Histórias |
|---|---|---|
| `frontend` | SPA em TypeScript (Nginx): catálogo, mapa de assentos com alternativa em lista, checkout com cronômetro, meus ingressos com QR, áreas do organizador e da portaria | US11 a US16, US19 a US21, US24, US26, US28, US31 |
| `api-gateway` | Entrada única, valida JWT, roteia `/api/*`, responde 503 se o serviço de destino cair | US01, US02, US04 |
| `inventario-service` | Eventos, lotes, setores e assentos; catálogo com cache; reserva atômica; expiração em 10 min; Outbox | US07, US08, US11, US12, US14 (API), US15, US16, US31 |
| `checkout-service` | Pedido (máquina de estados), pagamento idempotente, gateway com retry e circuit breaker, Outbox | US19, US20, US21, US23 |
| `emissao-service` | Ingresso com QR assinado e PDF, validação na portaria | US24, US26, US28, US29 |
| `gateway-pagamento` | Simulador de pagamento (aprova, recusa, atrasa, falha) | RNF10 |
| `libs/commons` | Outbox + relay, consumo idempotente, DLQ, papéis do Keycloak | RNF04, RNF05 |

Infraestrutura (Docker Compose): PostgreSQL, Redis, Kafka (KRaft) + Kafka UI, Keycloak com realm
importado e auto-cadastro, OpenTelemetry Collector, Jaeger, Prometheus e Grafana.
**Ainda não implementado** (Pacote 4): `iam-service`, `telemetria-service`, `realtime-service`
(a SPA atualiza a disponibilidade por polling de 5 s), e-mail, fila virtual e rate limit no gateway.

## Como rodar

Pré-requisitos: Docker (com Compose) e Java 21. O Maven vem pelo wrapper (`./mvnw`).

```bash
cp .env.example .env              # valores de desenvolvimento; nunca versione o .env
docker compose up -d --build --wait   # sobe tudo e espera os healthchecks (RNF19)
./scripts/smoke.sh                # fluxo ponta a ponta pelo gateway (inclui auto-cadastro e observabilidade)
docker compose down -v
```

Portas: **SPA `3000`**, API Gateway `8080`, Keycloak `8081`, Kafka UI `8089`, simulador de
pagamento `8090`, Prometheus `9090`, Jaeger `16686`, Grafana `3001` (dashboard "PulsePass - visão
geral"). As rotas `/actuator/prometheus` ficam abertas porque só a rede do Compose as alcança, exceto
a do gateway, que também responde na porta `8080` (configuração de desenvolvimento).
Usuários de teste (um por papel: cliente, organizador, operador, admin) estão no realm
`infra/keycloak/pulsepass-realm.json`, e a senha de desenvolvimento em `.env.example`.

Outros scripts de verificação (com o Compose no ar):

```bash
./scripts/replicas.sh      # 2 réplicas de cada serviço de negócio, partições divididas, smoke completo (RNF09)
./scripts/resiliencia.sh   # derruba a Emissão durante uma compra; o ingresso sai quando ela volta (RNF11)
./scripts/carga.sh         # k6: catálogo a 1.000 req/s e reserva a 200 req/s (RNF06 e RNF07)
```

Resultados em `docs/evidencias/`. Exemplo (token e reserva):

```bash
TOKEN=$(curl -s localhost:8081/realms/pulsepass/protocol/openid-connect/token \
  -d grant_type=password -d client_id=pulsepass-api \
  -d username=cliente@pulsepass.test --data-urlencode "password=$DEV_USER_PASSWORD" | jq -r .access_token)
curl -s localhost:8080/api/eventos                       # catálogo (público)
curl -s -X POST localhost:8080/api/reservas -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"eventoId":"...","assentoIds":["..."]}'
```

## Testes

```bash
./mvnw verify                      # unitários + integração (Testcontainers) + formatação + cobertura >= 70% no domínio
(cd frontend && npm ci && npm test && npm run build)   # tipos, testes e build da SPA
./mvnw -Pmutacao -pl services/inventario-service test-compile org.pitest:pitest-maven:mutationCoverage -DskipITs
```

Os testes de integração usam PostgreSQL, Redis e Kafka reais via Testcontainers (o Docker precisa
estar rodando). Principais garantias verificadas:

| Requisito | Teste |
|---|---|
| RNF01 zero sobre-venda | 500 reservas simultâneas no mesmo assento = 1 sucesso e 499 conflitos; 200 reservas num lote de 50 = 50 sucessos (`ReservaConcorrenteIT`) |
| RNF02 expiração | reserva não paga expira, libera o assento e publica `ReservaExpirada`; expiração suspensa durante o pagamento (`ExpiracaoEOutboxIT`) |
| RNF03 idempotência | mesma `Idempotency-Key` = 1 chamada ao gateway, resposta idêntica, 1 pagamento; 20 chamadas concorrentes = 1 pagamento (`CheckoutIT`) |
| RNF04 Outbox | com o Kafka pausado o evento fica pendente e é publicado quando ele volta (`ExpiracaoEOutboxIT`) |
| RNF05 consumo idempotente | o mesmo evento entregue duas vezes não duplica ingressos nem efeitos (`CheckoutIT`, `EmissaoIT`, `ConsumidorEApiIT`) |
| RNF10 resiliência | gateway com erro 500 = 3 tentativas com backoff, 503 ao cliente, pedido volta a RESERVADO (`CheckoutIT`) |
| RNF13 RBAC | 401 sem token e 403 por papel em todos os serviços; `smoke.sh` confirma pelo gateway |
| RNF06 e RNF07 carga | catálogo p95 = 71 ms a ~1.000 req/s; reserva p95 = 7,4 ms a ~200 req/s, 6.000 reservas, 0% de falhas (`docs/evidencias`; laptop, sem rede real) |
| RNF09 réplicas | `replicas.sh`: 2 réplicas de cada serviço, grupos Kafka com 2 membros, smoke completo |
| RNF11 emissão fora | `resiliencia.sh`: venda aprovada com a Emissão parada; ingresso emitido quando ela volta |
| RNF16 observabilidade | `smoke.sh`: um único trace atravessa gateway, Checkout, Emissão e Inventário (inclusive Outbox e Kafka); métricas no Prometheus; dashboard no Grafana |
| RNF22 acessibilidade | Lighthouse 100 nas páginas públicas; axe-core sem violações WCAG A/AA em todas as telas |
| RNF14 QR assinado | QR adulterado ou assinado com outro segredo é rejeitado; 50 leituras simultâneas liberam exatamente 1 (`EmissaoIT`) |

Limite honesto: a cobertura de 70% é exigida no pacote `domain` (máquinas de estado e regras
puras, que são pequenas). A lógica de orquestração (`*Service`) é exercida pelos testes de
integração, mas não entra nesse percentual.

## Estrutura

```
libs/commons/        Outbox, consumo idempotente, DLQ, papéis do Keycloak
services/*           um microsserviço por pasta (Spring Boot 3.5, Java 21)
frontend/            SPA (TypeScript, Vite) servida por Nginx
specs/               specs por funcionalidade (requirements, design, tasks) - processo SDD
docs/pacotes/        documentação dos Pacotes 1, 2 e 3
docs/evidencias/     resultados de carga e acessibilidade
docs/adr/            decisões arquiteturais
infra/               Dockerfile comum, realm do Keycloak, observabilidade, init do PostgreSQL e dos tópicos Kafka
scripts/             smoke, réplicas, resiliência e carga (k6)
.github/             CI (GitHub Actions), modelo de PR, CODEOWNERS, Dependabot
.claude/CLAUDE.md    regras dos agentes de IA
```

## Fluxo de trabalho (GCS)

`main` (releases/tags) e `develop` (integração) são protegidas; todo trabalho nasce de uma issue em
`feature/<id>-<descricao>` ou `fix/<id>-<descricao>`, com Conventional Commits, Pull Request
obrigatório, CI verde e revisão de integrante diferente do autor (squash merge). O PR indica o que
foi gerado por IA. Detalhes na seção 6 do documento do Pacote 2.

## Divergências em relação ao desenho do Pacote 2

Documentadas no Pacote 3 (`docs/pacotes`): idempotência como serviço em vez de filtro servlet; PDF
gerado sob demanda (sem armazenamento); tópicos criados por contêiner de inicialização, com
auto-criação desligada; `iam-service`, `telemetria-service` e `realtime-service` adiados para o
Pacote 4; o contexto de trace atravessa o Outbox pela coluna `traceparent`.
