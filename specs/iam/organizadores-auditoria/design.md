# IAM: desenho

- Banco `db_iam`: `solicitacao_organizador` (máquina PENDENTE → APROVADA | REJEITADA) e `auditoria` com chave natural única (deduplica a leitura repetida do Keycloak).
- `Diretorio` (interface) esconde a Admin API do Keycloak: `atribuirPapel(usuarioId, papel)` e `eventos(desde)`. `KeycloakAdmin` é a implementação real (client `pulsepass-iam` com conta de serviço, papéis `manage-users`, `view-users`, `view-events`); os testes usam um dublê.
- Se o Keycloak falhar ao atribuir o papel, a transação inteira volta atrás (a solicitação continua PENDENTE).
- `AuditoriaWorker` lê os eventos do Keycloak a cada 10 s a partir do último instante gravado.
- O realm passa a salvar eventos (`eventsEnabled`, `adminEventsEnabled`).
- Refresh em "chave-valor": as sessões e refresh tokens ficam no armazenamento de sessões do Keycloak (cache distribuído chave-valor); o iam-service não os replica (ADR-009).
