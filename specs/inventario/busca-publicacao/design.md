# Busca e publicação: desenho

- `FiltroCatalogo` (domínio, puro): recebe a lista já em cache e aplica os filtros em memória, então a busca não custa uma consulta ao banco (RNF06).
- `evento.status` já existe (`PUBLICADO` | `RASCUNHO`). A mudança é um `update ... where id = ? and organizador_id = ?` e uma linha afetada decide o dono.
- RBAC: `/eventos/meus` e os `POST .../publicar|despublicar|fila-ativa` só para o papel organizador (o `PUT` da fila também).
