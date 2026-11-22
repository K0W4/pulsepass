# IAM: organizadores e auditoria

Histórias: US03 (sessão renovada), US05 (cadastro de organizador com aprovação), US06 (auditoria de acessos). RNF12.

1. O cliente logado envia a solicitação de organizador (`POST /iam/organizadores/solicitacoes`) com razão social, CNPJ (14 dígitos com dígitos verificadores válidos) e contato. Só pode haver uma solicitação pendente por usuário.
2. O admin lista (`GET /iam/organizadores/solicitacoes?status=`), aprova ou rejeita. Aprovar atribui o papel `organizador` no Keycloak pela Admin API, grava a decisão na auditoria e publica `OrganizadorAprovado` em `iam.usuario.v1` pelo Outbox, na mesma transação.
3. A auditoria (`GET /iam/auditoria`, só admin) reúne os eventos do Keycloak (login, falha de login, logout, cadastro, troca de senha) e as decisões do próprio serviço, sem duplicar mesmo se a leitura do Keycloak se repetir.
4. A sessão é renovada pelo refresh token do Keycloak (token de 15 min, sessão de 7 dias); a SPA renova 30 s antes de expirar e há teste automatizado que prova a renovação (RNF12).
5. O serviço guarda só o identificador do usuário (`sub`); o e-mail não é persistido (RNF15).
6. Aprovar uma solicitação já decidida devolve 409.
