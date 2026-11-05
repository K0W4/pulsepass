# PulsePass

Plataforma distribuída de bilhetagem, reserva e acesso para megaeventos. Projeto integrador de
Construção de Software (PUCRS, 2026/2). Este repositório é o **Pacote 3 (checkpoint)**: a fatia
vertical **R1** do backend, do catálogo ao ingresso validado na portaria.

Integrantes: Gabriel Kowaleski e Mayra Bordin.

## O que já funciona (R1)

| Serviço | Responsabilidade | Histórias |
|---|---|---|
| `api-gateway` | Entrada única, valida JWT, roteia `/api/*` | US01, US02, US04 |
| `inventario-service` | Eventos, lotes, setores e assentos; catálogo com cache; reserva atômica; expiração em 10 min; Outbox | US07, US08, US11, US12, US14 (API), US15, US16, US31 |
| `checkout-service` | Pedido (máquina de estados), pagamento idempotente, gateway com retry e circuit breaker, Outbox | US19, US20, US21, US23 |
| `emissao-service` | Ingresso com QR assinado e PDF, validação na portaria | US24, US26, US28, US29 |
| `gateway-pagamento` | Simulador de pagamento (aprova, recusa, atrasa, falha) | RNF10 |
| `libs/commons` | Outbox + relay, consumo idempotente, DLQ, papéis do Keycloak | RNF04, RNF05 |

Infraestrutura (Docker Compose): PostgreSQL, Redis, Kafka (KRaft) + Kafka UI, Keycloak com realm
importado. **Ainda não implementado** (R2/R3, Pacote 4): SPA, `iam-service`, `telemetria-service`,
`realtime-service`, e-mail, fila virtual, observabilidade completa, rate limit no gateway.

## Como rodar

Pré-requisitos: Docker (com Compose) e Java 21. O Maven vem pelo wrapper (`./mvnw`).

```bash
cp .env.example .env              # valores de desenvolvimento; nunca versione o .env
docker compose up -d --build --wait   # sobe tudo e espera os healthchecks (RNF19)
./scripts/smoke.sh                # fluxo ponta a ponta pelo gateway
docker compose down -v
```

Portas: API Gateway `8080`, Keycloak `8081`, Kafka UI `8089`, simulador de pagamento `8090`.
Usuários de teste (um por papel: cliente, organizador, operador, admin) estão no realm
`infra/keycloak/pulsepass-realm.json`, e a senha de desenvolvimento em `.env.example`.

Exemplo (token e reserva):

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
| RNF14 QR assinado | QR adulterado ou assinado com outro segredo é rejeitado; 50 leituras simultâneas liberam exatamente 1 (`EmissaoIT`) |

Limite honesto: a cobertura de 70% é exigida no pacote `domain` (máquinas de estado e regras
puras, que são pequenas). A lógica de orquestração (`*Service`) é exercida pelos testes de
integração, mas não entra nesse percentual.

## Estrutura

```
libs/commons/        Outbox, consumo idempotente, DLQ, papéis do Keycloak
services/*           um microsserviço por pasta (Spring Boot 3.5, Java 21)
specs/               specs por funcionalidade (requirements, design, tasks) - processo SDD
docs/pacotes/        documentação dos Pacotes 1, 2 e 3
docs/adr/            decisões arquiteturais
infra/               Dockerfile comum, realm do Keycloak, init do PostgreSQL e dos tópicos Kafka
scripts/smoke.sh     teste ponta a ponta
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
auto-criação desligada; módulos de IAM, telemetria e tempo real adiados para o Pacote 4.
