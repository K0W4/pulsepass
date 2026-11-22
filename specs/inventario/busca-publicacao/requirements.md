# Busca e publicação de eventos

Histórias: US13 (buscar por nome, cidade e data), US10 (publicar e despublicar).

1. `GET /eventos?q=&local=&de=&ate=` filtra o catálogo público: `q` por parte do nome, `local` por parte do local (ignora maiúsculas e acentos), `de` e `ate` por data (AAAA-MM-DD, inclusive, no fuso de São Paulo). Sem parâmetros, devolve tudo, como hoje.
2. O dono do evento publica (`POST /eventos/{id}/publicar`) e despublica (`POST /eventos/{id}/despublicar`). Evento despublicado some do catálogo e não aceita reserva; reservas já feitas seguem válidas.
3. `POST /eventos` aceita `publicar` (padrão `true`); `false` cria como RASCUNHO.
4. `GET /eventos/meus` (organizador) lista os eventos do dono com o status, inclusive rascunhos.
5. Publicar ou despublicar invalida o cache do catálogo.
6. Quem não é o dono recebe 404.
