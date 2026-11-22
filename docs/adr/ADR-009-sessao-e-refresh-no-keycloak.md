# ADR-009: sessão e refresh token ficam no Keycloak

**Estado:** aceita (Pacote 4). **Contexto:** o enunciado pede autenticação stateless com JWT e *refresh tokens em chave-valor*.

**Decisão.** O token de acesso é um JWT assinado de 15 minutos, validado por cada serviço pelo JWKS do Keycloak (stateless). A sessão e o refresh token (7 dias) ficam no armazenamento de sessões do próprio Keycloak, que é um cache distribuído chave-valor (Infinispan). O `iam-service` **não replica** refresh tokens num Redis próprio.

**Por quê.** Replicar o refresh token fora do provedor criaria uma segunda fonte de verdade para revogação e rotação, sem ganho: o Keycloak já invalida a sessão no logout e na troca de senha. O que o `iam-service` acrescenta é governança: aprovação de organizadores (papel atribuído pela Admin API) e auditoria de acessos.

**Consequências.** A renovação da sessão é a do Keycloak (`grant_type=refresh_token`), usada pela SPA (`kc.updateToken(30)`). O smoke prova a renovação contra o Keycloak real (access token novo e aceito pelo gateway). Um token de acesso continua válido até expirar mesmo depois de um logout; o limite é de 15 minutos.
