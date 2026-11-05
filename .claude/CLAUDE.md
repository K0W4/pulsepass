# Regras dos agentes de IA (PulsePass)

Este arquivo é versionado (regra 2 do processo do Pacote 1) e vale para qualquer agente de código.

1. **Spec primeiro.** Toda funcionalidade tem `/specs/<modulo>/<feature>/{requirements,design,tasks}.md`; não invente regra de negócio, marque dúvidas como [ABERTO].
2. **Escopo.** Uma task por vez, só no serviço da task; commits pequenos em `feature/*`.
3. **Sem escrita dupla.** Estado e evento vão na mesma transação (`Outbox`); consumidores deduplicam por `eventId` (`ProcessedEvents`).
4. **Concorrência.** A verdade está no PostgreSQL (`UPDATE ... WHERE status = ...` e contagem de linhas); Redis só filtra.
5. **Testes.** Domínio com testes unitários (>= 70%) e mutação (>= 60%); integração com Testcontainers reais.
6. **Segredos e dados pessoais** nunca entram em código, prompt ou log.
7. **Revisão.** O agente de revisão só comenta; aprovação e merge são humanos.
8. **PR.** Indicar quais partes foram geradas por IA.
