# PulsePass

Plataforma distribuída de bilhetagem, reserva e acesso para megaeventos. Projeto integrador de
Construção de Software (PUCRS, 2026/2). Este repositório é o **Pacote 4 (produto completo)**: do
catálogo ao ingresso validado na portaria, com identidade e auditoria, tempo real por WebSocket,
telemetria, fila virtual, rate limit, e-mail do ingresso, SPA, observabilidade, testes de carga,
réplicas e teste de resiliência.

Integrantes: Gabriel Kowaleski e Mayra Bordin.

## O que funciona

| Serviço | Responsabilidade | Histórias |
|---|---|---|
| `frontend` | SPA em TypeScript (Nginx): catálogo com busca, mapa de assentos ao vivo com alternativa em lista, fila virtual, checkout com status em tempo real, meus ingressos com QR, áreas do organizador, da portaria (com leitura pela câmera) e da administração | US10 a US16, US18 a US21, US24 a US29, US31 a US33 |
| `api-gateway` | Entrada única, valida JWT, roteia `/api/*` (inclusive WebSocket), rate limit por cliente (429), responde 503 se o serviço de destino cair | US01, US02, US04 |
| `iam-service` | Solicitação e aprovação de organizador (papel atribuído no Keycloak pela Admin API), auditoria de acessos | US05, US06 |
| `inventario-service` | Eventos, lotes, setores e assentos; catálogo com cache e busca; publicar e despublicar; reserva atômica; expiração em 10 min; fila virtual; Outbox | US07 a US18, US31 |
| `checkout-service` | Pedido (máquina de estados), pagamento idempotente, gateway com retry e circuit breaker, Outbox | US19 a US23 |
| `emissao-service` | Ingresso com QR assinado e PDF, e-mail com os PDFs, validação na portaria | US24, US25, US26, US28, US29 |
| `realtime-service` | WebSocket: mudanças de assento por evento e status do pedido, a partir do Kafka | US14, US27, US32 |
| `telemetria-service` | Ingestão de navegação e eventos de domínio, agregação por minuto, painel, p95 da emissão, métricas operacionais | US32, US33, RNF08 |
| `gateway-pagamento` | Simulador de pagamento (aprova, recusa, atrasa, falha) | RNF10 |
| `libs/commons` | Outbox + relay, consumo idempotente, DLQ, papéis do Keycloak | RNF04, RNF05 |

Infraestrutura (Docker Compose): PostgreSQL, Redis, Kafka (KRaft) + Kafka UI, Keycloak com realm
importado e auto-cadastro, Mailpit (caixa de entrada de desenvolvimento), OpenTelemetry Collector,
Jaeger, Prometheus e Grafana.

**Limites conhecidos:** o rate limit conta por réplica do gateway (ADR-010); o canal WebSocket do
pedido recebe o token na URL e não confere o dono do pedido (ADR-011); a leitura do QR pela câmera
usa a API `BarcodeDetector`, só disponível no Chrome e no Edge (Android e macOS), e nos demais a
portaria digita o código; o e-mail do comprador fica no corpo do evento `PedidoPago` (7 dias no
Kafka) e só é apagado da tabela da Emissão (ADR-013); as proteções de branch do GitHub e as issues
não foram configuradas.

## Como rodar

Pré-requisitos: Docker (com Compose), Java 21 e Node 22 (para os scripts). O Maven vem pelo wrapper
(`./mvnw`).

```bash
cp .env.example .env              # valores de desenvolvimento; nunca versione o .env
docker compose up -d --build --wait   # sobe tudo e espera os healthchecks (RNF19)
./scripts/smoke.sh                # fluxo ponta a ponta pelo gateway (todas as funcionalidades)
docker compose down -v
```

Portas: **SPA `3000`**, API Gateway `8080`, Keycloak `8081`, Kafka UI `8089`, simulador de
pagamento `8090`, **Mailpit `8025`** (e-mails dos ingressos), Prometheus `9090`, Jaeger `16686`,
Grafana `3001` (dashboard "PulsePass - visão geral"). As rotas `/actuator/prometheus` ficam abertas
porque só a rede do Compose as alcança, exceto a do gateway, que também responde na porta `8080`
(configuração de desenvolvimento).
Usuários de teste (um por papel: cliente, organizador, operador, admin) estão no realm
`infra/keycloak/pulsepass-realm.json`, e a senha de desenvolvimento em `.env.example`. A conta de
serviço do `iam-service` (client `pulsepass-iam`) usa um segredo só de desenvolvimento.

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
curl -s 'localhost:8080/api/eventos?q=show&local=porto'  # catálogo (público), com busca
curl -s -X POST localhost:8080/api/reservas -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"eventoId":"...","assentoIds":["..."]}'
```

## Testes

```bash
./mvnw verify                      # unitários + integração (Testcontainers) + formatação + cobertura >= 70% no domínio
(cd frontend && npm ci && npm test && npm run build)   # tipos, testes e build da SPA
./mvnw -q install -DskipTests -pl libs/commons -am   # uma vez: instala a commons no repositório local
for s in inventario checkout emissao iam telemetria realtime; do
  ./mvnw -Pmutacao -f services/$s-service/pom.xml test-compile org.pitest:pitest-maven:mutationCoverage -DskipITs
done
```

Os testes de integração usam PostgreSQL, Redis, Kafka e Mailpit reais via Testcontainers (o Docker
precisa estar rodando). Principais garantias verificadas:

| Requisito | Teste |
|---|---|
| RNF01 zero sobre-venda | 500 reservas simultâneas no mesmo assento = 1 sucesso e 499 conflitos; 200 reservas num lote de 50 = 50 sucessos (`ReservaConcorrenteIT`) |
| RNF02 expiração | reserva não paga expira, libera o assento e publica `ReservaExpirada`; expiração suspensa durante o pagamento (`ExpiracaoEOutboxIT`) |
| RNF03 idempotência | mesma `Idempotency-Key` = 1 chamada ao gateway, resposta idêntica, 1 pagamento; 20 chamadas concorrentes = 1 pagamento (`CheckoutIT`) |
| RNF04 Outbox | com o Kafka pausado o evento fica pendente e é publicado quando ele volta (`ExpiracaoEOutboxIT`) |
| RNF05 consumo idempotente | o mesmo evento entregue duas vezes não duplica ingressos, e-mails nem contagens (`CheckoutIT`, `EmissaoIT`, `NotificacaoIT`, `TelemetriaIT`, `ConsumidorEApiIT`) |
| RNF08 ingresso em 30 s (p95) | `TelemetriaIT` (p95 nas duas ordens de chegada e meta estourada); `smoke.sh` mede o p95 real |
| RNF10 resiliência | gateway com erro 500 = 3 tentativas com backoff, 503 ao cliente, pedido volta a RESERVADO (`CheckoutIT`) |
| RNF12 sessão renovada | `smoke.sh`: o refresh token devolve um access token novo, aceito pelo gateway; tempos de 15 min e 7 dias |
| RNF13 RBAC | 401 sem token e 403 por papel em todos os serviços; `smoke.sh` confirma pelo gateway |
| RNF06 e RNF07 carga | catálogo p95 = 71 ms a ~1.000 req/s; reserva p95 = 7,4 ms a ~200 req/s, 6.000 reservas, 0% de falhas (`docs/evidencias`; laptop, sem rede real) |
| RNF09 réplicas | `replicas.sh`: 2 réplicas de cada serviço, grupos Kafka com 2 membros, smoke completo |
| RNF11 emissão fora | `resiliencia.sh`: venda aprovada com a Emissão parada; ingresso emitido quando ela volta. Com o SMTP fora, o ingresso sai e o e-mail é tentado de novo (`NotificacaoIT`) |
| RNF14 QR assinado | QR adulterado ou assinado com outro segredo é rejeitado; 50 leituras simultâneas liberam exatamente 1 (`EmissaoIT`) |
| RNF15 LGPD | o endereço de e-mail some da tabela depois do envio ou da falha definitiva (`NotificacaoIT`); o Checkout não grava o e-mail (`CheckoutIT`); a navegação é anônima e de lista fechada (`TelemetriaIT`) |
| RNF16 observabilidade | `smoke.sh`: um único trace atravessa gateway, Checkout, Emissão e Inventário (inclusive Outbox e Kafka); métricas no Prometheus; dashboard no Grafana |
| RNF22 acessibilidade | Lighthouse 100 nas páginas públicas; axe-core sem violações WCAG A/AA em todas as telas |
| RNF23 fila virtual | ordem de chegada, admissão por taxa, bloqueio e consumo da admissão (`FilaVirtualIT`); `smoke.sh` pelo gateway |
| US05 e US06 IAM | aprovação com Keycloak real (`smoke.sh`), rollback se o Keycloak falha, auditoria sem duplicar (`IamIT`, `KeycloakAdminTest`) |
| US10 e US13 | rascunho, publicação e busca por nome, local e data (`BuscaPublicacaoIT`, `FiltroCatalogoTest`) |
| US14 e US27 tempo real | Kafka real e clientes WebSocket reais: só o canal assinado recebe, token exigido (`RealtimeIT`); `smoke.sh` pelo gateway e pelo Nginx |
| US25 e-mail | Mailpit real: um PDF por ingresso, sem duplicata, tentativas e falha (`NotificacaoIT`) |
| rate limit | 429 com `Retry-After`, balde por cliente, isenções (`RateLimitTest`); `smoke.sh` com 600 requisições simultâneas |

Limite honesto: a cobertura de 70% é exigida no pacote `domain` (máquinas de estado e regras
puras, que são pequenas). A lógica de orquestração (`*Service`) é exercida pelos testes de
integração, mas não entra nesse percentual.

## Estrutura

```
libs/commons/        Outbox, consumo idempotente, DLQ, papéis do Keycloak
services/*           um microsserviço por pasta (Spring Boot 3.5, Java 21)
frontend/            SPA (TypeScript, Vite) servida por Nginx
specs/               specs por funcionalidade (requirements, design, tasks) - processo SDD
docs/pacotes/        documentação dos Pacotes 1, 2, 3 e 4 (fontes HTML e PDFs)
docs/evidencias/     resultados de carga, acessibilidade e do Pacote 4
docs/adr/            decisões arquiteturais (ADR-009 em diante)
infra/               Dockerfile comum, realm do Keycloak, observabilidade, init do PostgreSQL e dos tópicos Kafka
scripts/             smoke, réplicas, resiliência e carga (k6)
.github/             CI (GitHub Actions), modelo de PR, CODEOWNERS, Dependabot
.claude/CLAUDE.md    regras dos agentes de IA
```

## Fluxo de trabalho (GCS)

`main` (releases/tags) e `develop` (integração) são o tronco; o trabalho novo nasce em
`feature/<descricao>` ou `fix/<descricao>`, com Conventional Commits, Pull Request, CI verde e
revisão (squash merge). O PR indica o que foi gerado por IA. Detalhes na seção 6 do documento do
Pacote 2. As proteções de branch (checks obrigatórios e revisão de integrante diferente do autor)
ainda precisam ser ligadas no GitHub (ver o documento do Pacote 4, seção de limites).

## Divergências em relação ao desenho do Pacote 2

Documentadas nos Pacotes 3 e 4 (`docs/pacotes`): idempotência como serviço em vez de filtro
servlet; PDF gerado sob demanda (sem armazenamento); tópicos criados por contêiner de
inicialização, com auto-criação desligada; o contexto de trace atravessa o Outbox pela coluna
`traceparent`; no Pacote 4, o refresh token fica no Keycloak (ADR-009), o rate limit é por réplica
(ADR-010) e o e-mail é enviado por um worker da Emissão (ADR-013).
