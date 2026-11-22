"""Gera src/pacote1_atualizado.html: o Pacote 1 reconstruído a partir do texto entregue em 14/10 (o original
só existia como PDF), no padrão visual dos demais pacotes, com o estado final de cada história e de cada
requisito não funcional. As imagens (story map, mapa de navegação e mockups) vêm de src/img, extraídas do PDF
entregue. Uso (de docs/pacotes): python3 src/gerar_pacote1.py REPO_URL   (rode antes gerar_pacote4.py)
"""
import json, re, sys

repo = sys.argv[1] if len(sys.argv) > 1 else 'REPO_URL'
estado = json.load(open('src/estado-final.json', encoding='utf8'))
css0 = re.search(r'<style>(.*?)</style>', open('src/pacote2.html', encoding='utf8').read(), re.S).group(1)
css = css0.replace('PulsePass · Pacote 2 · ', 'PulsePass · Pacote 1 · ')
css += '''
  .fig img { max-width: 100%; max-height: 190mm; }
  .fig.mock img { max-height: 112mm; }
  .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
  .gh { background: #f6f8fb; border-left: 4px solid #12305a; padding: 4px 10px; margin: 6px 0; font-size: 9.2pt; }
  .gh p { margin: 2px 0; }
'''


def tabela(cab, linhas, larg=None):
    th = ''.join('<th%s>%s</th>' % ((' style="width:%s"' % larg[i]) if larg else '', c) for i, c in enumerate(cab))
    tr = ''.join('<tr>' + ''.join('<td>%s</td>' % c for c in l) + '</tr>' for l in linhas)
    return '<table><thead><tr>%s</tr></thead>%s</table>' % (th, tr)


c = lambda s: '<code>%s</code>' % s
PEND = 'Pendente'

# ------------------------------------------------------------------ ferramentas e agentes
ferramentas = [
 ('Assistente/agente de código', 'Claude Code (CLI) e GitHub Copilot (IDE)', 'Geração de specs, implementação por task, testes, refatoração e revisão de PR', 'Claude Code (specs, código, testes e documentação; regras em ' + c('.claude/CLAUDE.md') + ')'),
 ('Gestão do backlog', 'GitHub Projects (quadro Kanban + Story Map)', 'Épicos como <i>milestones</i>, histórias como <i>issues</i> com rótulos de release', 'Story Map e backlog neste documento; o quadro e as <i>issues</i> do GitHub não foram criados'),
 ('Versionamento e GCS', 'Git + GitHub (Git Flow simplificado)', c('main') + ', ' + c('develop') + ', ' + c('feature/*') + ', ' + c('fix/*') + '; PR obrigatório', 'Git com ramos ' + c('feature/*') + ' e Conventional Commits; a proteção de branch e a revisão cruzada ainda não estão ligadas'),
 ('CI/CD', 'GitHub Actions + Docker Compose', 'Lint, testes unitários/integração, build de imagens', 'GitHub Actions (lint, testes, front-end, smoke, segredos, mutação, publicação no GHCR) e Docker Compose'),
 ('Design / UX', 'Figma', 'Mockups (seção 5) e protótipo navegável', 'Os mockups da seção 5 (arquivos de imagem); a SPA os seguiu'),
 ('Documentação arquitetural', 'Markdown + Mermaid / Structurizr (C4)', 'Diagramas versionados junto ao código (Pacote 2)', 'Mermaid e SVG nos documentos do Pacote 2; ADRs em ' + c('docs/adr')),
 ('Qualidade', 'JUnit 5, Testcontainers, PIT, SonarLint', 'Cobertura > 70% do domínio, testes de integração e mutação', 'JUnit 5, Testcontainers, PIT, JaCoCo, Spotless, k6, Lighthouse e axe-core'),
]
agentes = [
 ('Spec Agent', 'História + RNFs → requirements.md, design.md, tasks.md', 'Não inventa regra de negócio: marca dúvidas como [ABERTO] para o time decidir'),
 ('Code Agent', 'Task aprovada → código em branch ' + c('feature/*'), 'Escopo limitado ao serviço da task; sem acesso a segredos; commits pequenos'),
 ('Test Agent', 'Critérios de aceite → testes unitários e de integração', 'Testes devem falhar antes da implementação; mutação ≥ 60% nos módulos críticos'),
 ('Review Agent', 'Diff do PR → comentários de revisão', 'Apenas sugere; nunca aprova nem faz merge'),
 ('Docs Agent', 'Código + specs → README, ADRs e diagramas C4', 'Documentação revisada no mesmo PR da mudança'),
]
stake = [
 ('Cliente (comprador)', 'Público final que compra ingressos, geralmente pelo celular e no pico de abertura de vendas.', 'Ver disponibilidade real, garantir o assento, pagar sem cobrança duplicada, receber o ingresso na hora.'),
 ('Organizador', 'Produtora responsável pelo evento.', 'Cadastrar eventos, lotes e mapas de assentos; acompanhar vendas em tempo real.'),
 ('Operador', 'Equipe de portaria/controle de acesso no dia do evento.', 'Validar QR Codes rapidamente e impedir uso duplicado de ingressos.'),
 ('Administrador', 'Equipe da plataforma PulsePass.', 'Aprovar organizadores, gerir papéis, auditar acessos e monitorar a saúde do sistema.'),
 ('Sistemas externos', 'Gateway de pagamento (simulado) e serviço de e-mail/notificação.', 'Integração assíncrona e tolerante a falhas.'),
]
modulos = [
 ('Identidade e Governança (IAM)', 'Login com JWT e refresh token (Keycloak), RBAC com 4 papéis, cadastro e aprovação de organizadores, auditoria de credenciais.'),
 ('Inventário e Concorrência', 'Eventos, lotes e mapas de assentos; catálogo de alta leitura (cache); bloqueio e liberação atômica de assentos; eventos de expiração de reserva.'),
 ('Transações e Checkout', 'Máquina de estados do pedido (Criado → Reservado → Aguardando → Pago / Expirado), Idempotency-Key, Transactional Outbox, integração com gateway simulado.'),
 ('Emissão e Notificações', 'Consumo de pagamentos confirmados, ingresso em PDF com QR Code e carimbo de tempo, envio por e-mail, atualização de tela em tempo real (WebSocket).'),
 ('Telemetria e DevOps', 'Ingestão de eventos de navegação e compra, painel de métricas em tempo real, CI/CD e orquestração com Docker Compose.'),
]
metricas = [
 ('0 casos de sobre-venda em teste de carga com concorrência simulada no mesmo assento.', 'Atendida: 500 reservas simultâneas no mesmo assento, 1 sucesso (' + c('ReservaConcorrenteIT') + ').'),
 ('100% das reservas não pagas liberadas em até 10 min + 30 s.', 'Atendida (' + c('ExpiracaoEOutboxIT') + ').'),
 ('0 cobranças duplicadas em requisições repetidas com a mesma Idempotency-Key.', 'Atendida (' + c('CheckoutIT') + ': 20 chamadas concorrentes, 1 pagamento).'),
 ('Ingresso disponível ao comprador em até 30 s após a confirmação do pagamento (p95).', 'Atendida e medida pelo ' + c('telemetria-service') + ' (ver o Pacote 4).'),
 ('Validação de ingresso na portaria em menos de 1 s (p95).', 'Validação por uma única atualização condicional no banco; 50 leituras simultâneas liberam exatamente 1 (' + c('EmissaoIT') + '). Latência p95 da rota não foi medida em separado.'),
]

# ------------------------------------------------------------------ backlog
epicos = [
 ('EP1', 'Identidade e Acesso', 'Usuários se autenticam com segurança e acessam apenas o que seu papel permite.', 'IAM'),
 ('EP2', 'Gestão de Eventos', 'Organizadores publicam eventos, lotes e mapas de assentos.', 'Inventário'),
 ('EP3', 'Descoberta e Reserva', 'Compradores encontram eventos e reservam assentos sem conflito.', 'Inventário'),
 ('EP4', 'Checkout e Pagamento', 'Pedidos são pagos uma única vez, com estados consistentes.', 'Checkout'),
 ('EP5', 'Emissão e Acesso', 'Ingressos são emitidos, entregues e validados na entrada.', 'Emissão'),
 ('EP6', 'Operação e Telemetria', 'Organizadores e admins acompanham vendas e saúde do sistema.', 'Telemetria'),
]
hist = [
 ('US01', 'EP1', 'Como cliente, quero criar uma conta com e-mail e senha, para poder comprar ingressos.', 'R1'),
 ('US02', 'EP1', 'Como usuário, quero entrar na plataforma e receber um token de acesso, para usar as funções do meu perfil sem logar a cada tela.', 'R1'),
 ('US03', 'EP1', 'Como usuário, quero que minha sessão seja renovada automaticamente, para não perder uma compra por expiração do token.', 'R2'),
 ('US04', 'EP1', 'Como administrador, quero que cada rota exija o papel adequado (Cliente, Organizador, Operador, Admin), para proteger as operações sensíveis.', 'R1'),
 ('US05', 'EP1', 'Como organizador, quero solicitar meu cadastro com dados da empresa, para que um admin aprove meu acesso.', 'R2'),
 ('US06', 'EP1', 'Como administrador, quero consultar o histórico de logins e alterações de credenciais, para auditar acessos suspeitos.', 'R3'),
 ('US07', 'EP2', 'Como organizador, quero cadastrar um evento com nome, local, data e imagem, para divulgá-lo na plataforma.', 'R1'),
 ('US08', 'EP2', 'Como organizador, quero criar lotes com preço, quantidade e período de venda, para controlar a precificação.', 'R1'),
 ('US09', 'EP2', 'Como organizador, quero definir setores e assentos numerados, para vender lugares marcados.', 'R2'),
 ('US10', 'EP2', 'Como organizador, quero publicar ou despublicar um evento, para controlar quando as vendas abrem.', 'R2'),
 ('US11', 'EP3', 'Como cliente, quero ver a lista de eventos disponíveis, para escolher o que me interessa.', 'R1'),
 ('US12', 'EP3', 'Como cliente, quero ver detalhes, lotes e disponibilidade de um evento, para decidir a compra.', 'R1'),
 ('US13', 'EP3', 'Como cliente, quero buscar por nome, cidade e data, para encontrar eventos rapidamente.', 'R2'),
 ('US14', 'EP3', 'Como cliente, quero escolher assentos em um mapa que mostra o que está livre, reservado ou vendido, para escolher meu lugar.', 'R1'),
 ('US15', 'EP3', 'Como cliente, quero que os assentos escolhidos fiquem reservados para mim por 10 minutos, para concluir o pagamento com calma.', 'R1'),
 ('US16', 'EP3', 'Como plataforma, quero liberar automaticamente reservas não pagas após 10 minutos, para devolver os assentos à venda.', 'R1'),
 ('US17', 'EP3', 'Como cliente, quero cancelar minha reserva, para liberar os assentos se desistir.', 'R2'),
 ('US18', 'EP3', 'Como cliente, quero entrar em uma fila virtual quando a demanda estiver alta, para ter uma chance justa de comprar.', 'R2'),
 ('US19', 'EP4', 'Como cliente, quero revisar meu pedido com itens e total, para confirmar antes de pagar.', 'R1'),
 ('US20', 'EP4', 'Como cliente, quero pagar meu pedido, para garantir os ingressos.', 'R1'),
 ('US21', 'EP4', 'Como cliente, quero que um clique repetido em "Pagar" não gere duas cobranças, para não ser cobrado em dobro.', 'R1'),
 ('US22', 'EP4', 'Como plataforma, quero publicar o evento de pedido pago pelo padrão Outbox, para que nenhum pagamento confirmado deixe de gerar ingresso.', 'R2'),
 ('US23', 'EP4', 'Como cliente, quero ser avisado se o pagamento for recusado e poder tentar de novo dentro do prazo da reserva.', 'R2'),
 ('US24', 'EP5', 'Como cliente, quero receber meu ingresso em PDF com QR Code após o pagamento, para apresentar na entrada.', 'R1'),
 ('US25', 'EP5', 'Como cliente, quero receber o ingresso por e-mail, para ter uma cópia fora da plataforma.', 'R2'),
 ('US26', 'EP5', 'Como cliente, quero ver todos os meus ingressos em uma página, para acessá-los no dia do evento.', 'R1'),
 ('US27', 'EP5', 'Como cliente, quero ver o status do pedido mudar em tempo real (aguardando → pago → emitido), sem recarregar a página.', 'R2'),
 ('US28', 'EP5', 'Como operador, quero ler o QR Code do ingresso e ver se é válido, para liberar a entrada.', 'R1'),
 ('US29', 'EP5', 'Como operador, quero que um ingresso já utilizado seja recusado, para evitar fraudes.', 'R2'),
 ('US31', 'EP6', 'Como organizador, quero ver total vendido e ocupação por lote, para acompanhar meu evento.', 'R1'),
 ('US32', 'EP6', 'Como organizador, quero um painel atualizado em tempo real durante a abertura das vendas, para reagir rapidamente.', 'R2'),
 ('US33', 'EP6', 'Como administrador, quero ver métricas operacionais (latência, erros, lag do Kafka), para garantir a saúde da plataforma.', 'R3'),
]
hist_linhas = [(i, e, t, r, estado['us'].get(i, PEND)) for i, e, t, r in hist]

rnf = [
 ('RNF01', 'Consistência', 'O sistema não pode vender o mesmo assento ou ultrapassar a capacidade de um lote (zero oversell).', 'Teste concorrente com 500 requisições simultâneas no mesmo assento → exatamente 1 sucesso.'),
 ('RNF02', 'Consistência', 'Reservas não pagas expiram em 10 min e liberam os assentos.', 'Liberação em até 10 min + 30 s em 100% dos casos de teste.'),
 ('RNF03', 'Confiabilidade', 'Operações de pagamento são idempotentes via cabeçalho Idempotency-Key.', 'Requisições repetidas não geram cobrança nem registro duplicado.'),
 ('RNF04', 'Confiabilidade', 'Eventos de domínio são publicados pelo padrão Transactional Outbox (sem dual-write).', 'Queda do Kafka durante o pagamento não perde eventos; publicação ocorre ao retornar.'),
 ('RNF05', 'Confiabilidade', 'Consumidores Kafka são idempotentes (deduplicação por ID do evento).', 'Reprocessar o mesmo evento não gera ingresso duplicado.'),
 ('RNF06', 'Desempenho', 'Catálogo de eventos responde em p95 ≤ 200 ms sob 1.000 req/s (leitura via cache Redis).', 'Teste de carga (k6/Gatling) — bônus.'),
 ('RNF07', 'Desempenho', 'Reserva de assento responde em p95 ≤ 500 ms sob 200 reservas/s.', 'Teste de carga — bônus.'),
 ('RNF08', 'Desempenho', 'Ingresso emitido em até 30 s (p95) após confirmação do pagamento.', 'Medição pelo painel de telemetria.'),
 ('RNF09', 'Escalabilidade', 'Serviços sem estado em memória, podendo ter múltiplas réplicas; tópicos Kafka particionados por evento.', 'Subir 2 réplicas no Compose sem erro funcional.'),
 ('RNF10', 'Resiliência', 'Chamadas ao gateway usam timeout, retentativa com backoff e circuit breaker.', 'Gateway simulado com falha/atraso não derruba o checkout.'),
 ('RNF11', 'Disponibilidade', 'Falha do serviço de Emissão ou Notificação não impede a venda; mensagens ficam no Kafka até o reprocessamento.', 'Teste derrubando o serviço durante compras.'),
 ('RNF12', 'Segurança', 'Autenticação stateless com JWT assinado (access token 15 min, refresh token 7 dias armazenado em chave-valor) via Keycloak.', 'Token expirado/adulterado → HTTP 401.'),
 ('RNF13', 'Segurança', 'Autorização RBAC em todas as rotas (Cliente, Organizador, Operador, Admin).', 'Testes de integração por papel → HTTP 403 quando indevido.'),
 ('RNF14', 'Segurança', 'QR Code contém identificador assinado, impossível de forjar a partir de dados públicos.', 'QR alterado é rejeitado na validação.'),
 ('RNF15', 'Privacidade', 'Dados pessoais tratados conforme a LGPD: mínimo necessário, senhas apenas no IdP, logs sem dados sensíveis.', 'Revisão de código e de logs.'),
 ('RNF16', 'Observabilidade', 'Logs estruturados com correlation-id, métricas e rastreamento distribuído (OpenTelemetry, Prometheus, Grafana).', 'Uma compra é rastreável ponta a ponta em um único trace.'),
 ('RNF17', 'Manutenibilidade', 'Cobertura de testes > 70% no domínio (máquinas de estado); testes de integração com Testcontainers (PostgreSQL, Kafka, Redis).', 'Relatório de cobertura no CI.'),
 ('RNF18', 'Manutenibilidade', 'Código passa por linter e revisão; arquitetura documentada em C4 e ADRs.', 'Pipeline falha se o lint falhar.'),
 ('RNF19', 'Portabilidade', 'Todo o ambiente sobe com um único docker compose up (backend, frontend, bancos, Kafka e UI de mensageria).', 'Execução em máquina limpa.'),
 ('RNF20', 'Integração contínua', 'Pipeline no GitHub Actions executa lint, testes e build em cada PR e merge na branch develop.', 'PR bloqueado se o pipeline falhar.'),
 ('RNF21', 'Usabilidade', 'SPA responsiva (360 px a 1920 px); compra concluída em no máximo 5 telas a partir do detalhe do evento.', 'Teste com usuários / inspeção do fluxo.'),
 ('RNF22', 'Acessibilidade', 'Front-end segue WCAG 2.1 nível AA (contraste, navegação por teclado, textos alternativos); mapa de assentos com alternativa em lista.', 'Auditoria com Lighthouse/axe ≥ 90.'),
 ('RNF23', 'Equidade', 'Na abertura de vendas, o acesso ao checkout respeita ordem de chegada (fila virtual) quando a capacidade é excedida.', 'Teste de carga com fila ativa.'),
]
rnf_linhas = [(i, cat, t, crit, estado['rnf'].get(i, PEND)) for i, cat, t, crit in rnf]

telas = [
 ('Tela 1 · Home/Catálogo', 'US11, US13', 'Implementada: lista de eventos com busca por nome, local e datas.'),
 ('Tela 2 · Detalhe do evento', 'US12', 'Implementada: lotes e disponibilidade.'),
 ('Tela 3 · Mapa de assentos', 'US14, US15', 'Implementada: mapa com alternativa em lista; estados atualizados por WebSocket; mensagem de assento já reservado; espera na fila virtual.'),
 ('Tela 4 · Checkout', 'US17, US19 a US21', 'Implementada: contagem regressiva, pagamento idempotente, cancelamento.'),
 ('Tela 5 · Status do pedido', 'US23, US27', 'Implementada dentro do checkout: o texto do status muda em tempo real (WebSocket) até a emissão.'),
 ('Tela 6 · Meus ingressos', 'US24 a US26', 'Implementada: QR Code e download do PDF; o e-mail traz os mesmos PDFs.'),
 ('Tela 7 · Painel do organizador', 'US07 a US10, US31, US32', 'Implementada: meus eventos (publicar, despublicar, fila), formulários, painel de vendas ao vivo e painel analítico.'),
 ('Tela 8 · Leitor de QR do operador', 'US28, US29', 'Implementada: digitação do código e leitura pela câmera (Chrome e Edge no Android e no macOS).'),
 ('Tela 9 · Cadastro de evento em etapas', 'US07 a US09', 'Implementada como três formulários na área do organizador (evento, lote e setor), não como assistente em etapas.'),
 ('Tela 10 · Administração', 'US04 a US06, US33', 'Implementada: aprovação de organizadores, auditoria de acessos, saúde da plataforma e painel analítico. A gestão de usuários e papéis fica no console do Keycloak.'),
]

passos = [
 ('Descoberta.', 'O Product Owner do grupo seleciona histórias do Story Map (seção 3) que atendem à Definition of Ready: critérios de aceite escritos, dependências identificadas e mockup associado.'),
 ('Especificação.', 'Para cada história/feature é criada uma pasta em ' + c('/specs') + ' com três arquivos: ' + c('requirements.md') + ' (histórias e critérios de aceite), ' + c('design.md') + ' (contratos de API, eventos Kafka, modelo de dados, decisões) e ' + c('tasks.md') + ' (passos pequenos e verificáveis). O rascunho é gerado pelo agente de especificação a partir da história e dos RNFs.'),
 ('Revisão da spec.', 'Um integrante diferente do autor revisa e aprova a spec via PR. Spec aprovada = contrato; mudanças posteriores voltam por esta etapa.'),
 ('Implementação.', 'O agente de código executa as tasks uma a uma em branch ' + c('feature/*') + ', com contexto restrito à spec e ao módulo (microsserviço) correspondente.'),
 ('Testes.', 'O agente de testes gera testes unitários a partir dos critérios de aceite (foco nas máquinas de estado) e testes de integração com Testcontainers (PostgreSQL, Kafka, Redis). Teste de mutação (PIT) é usado para validar a qualidade dos testes gerados.'),
 ('Pull Request.', 'O agente de revisão comenta o PR (padrões, segurança, aderência à spec); a aprovação final e o merge em develop são feitos por um humano.'),
 ('CI/CD.', 'GitHub Actions executa lint, testes e build a cada PR/merge em develop; o ambiente sobe completo por docker-compose.'),
 ('Retrospectiva.', 'Ao fim da sprint, o grupo revisa métricas (lead time, retrabalho, bugs que escaparam) e atualiza o arquivo de regras dos agentes e os templates de spec.'),
]

ciclo = '''flowchart TB
  A["1 Descoberta<br/>(PO)"] --> B["2 Especificação<br/>(Spec Agent)"] --> C["3 Revisão da spec<br/>(humano)"] --> D["4 Implementação<br/>(Code Agent)"]
  D --> E["5 Testes<br/>(Test Agent, PIT)"] --> F["6 Pull Request<br/>(Review Agent comenta, humano aprova)"] --> G["7 CI/CD<br/>(GitHub Actions)"] --> H["8 Retrospectiva<br/>(humano)"]
  H -.-> A
  classDef humano fill:#fff4e8,stroke:#e8731a,stroke-width:2px,color:#1b1b1b
  classDef agente fill:#e8eef7,stroke:#12305a,stroke-width:2px,color:#1b1b1b
  class A,C,F,H humano
  class B,D,E,G agente'''

gherkin = [
 ('US15 — Reserva atômica de assentos', ['Dado que o assento A10 está LIVRE', 'Quando dois clientes tentam reservá-lo ao mesmo tempo', 'Então apenas um recebe a reserva (status RESERVADO, expira em 10 min) e o outro recebe HTTP 409 "assento indisponível"', 'E o mapa de ambos mostra A10 como reservado em até 2 s']),
 ('US16 — Expiração de reserva', ['Dado um pedido RESERVADO há 10 minutos sem pagamento', 'Quando o prazo expira', 'Então o pedido passa para EXPIRADO, os assentos voltam para LIVRE e o evento "reserva.expirada" é publicado no Kafka']),
 ('US21 — Pagamento idempotente', ['Dado um pedido AGUARDANDO pagamento', 'Quando o cliente envia POST /pagamentos duas vezes com a mesma Idempotency-Key', 'Então o gateway é chamado apenas uma vez, a segunda resposta é idêntica à primeira e existe somente um registro de pagamento']),
 ('US28/US29 — Validação na portaria', ['Dado um ingresso EMITIDO e nunca utilizado', 'Quando o operador lê o QR Code', 'Então a tela mostra "Entrada liberada" com nome do evento e setor e o ingresso passa para UTILIZADO', 'Quando o mesmo QR é lido novamente', 'Então a tela mostra "Ingresso já utilizado" com horário da primeira leitura']),
]

specs = '''<pre>/specs
  /iam/organizadores-auditoria/     requirements.md · design.md · tasks.md
  /inventario/reserva-atomica/
  /inventario/busca-publicacao/
  /inventario/fila-virtual/         (inclui o rate limit do gateway)
  /checkout/pagamento-idempotente/
  /emissao/ingresso-qr/
  /emissao/email-comprovante/
  /realtime/tempo-real/
  /telemetria/painel-tempo-real/
/.claude/CLAUDE.md                  regras dos agentes (padrões de código, arquitetura, proibições)
/docs/adr/                          decisões arquiteturais (ADR-001 a ADR-008 no Pacote 2; ADR-009 em diante aqui)</pre>'''

H = lambda s: s
html = '''<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>PulsePass – Pacote 1 (atualizado)</title><style>@@CSS@@</style></head><body>
<div class="cover">
  <p>PONTIFÍCIA UNIVERSIDADE CATÓLICA DO RIO GRANDE DO SUL<br>ESCOLA POLITÉCNICA<br>46504-04 · Construção de Software · Turma 330 · 2026/2<br>Prof. Michael da Costa Mora</p>
  <p style="margin-top:14mm"><small>PROJETO INTEGRADOR · PACOTE 1</small></p>
  <h1>PulsePass</h1>
  <h2>Plataforma Distribuída de Bilhetagem, Reserva e Acesso para Megaeventos</h2>
  <p><b>Processo de Desenvolvimento e Requisitos</b></p>
  <p>Processo com IA (SDD) · Visão do Produto · Story Map · RNFs · Mockups e Navegação</p>
  <p style="margin-top:20mm">Integrantes: Gabriel Kowaleski e Mayra Bordin</p>
  <p>Repositório: <b>@@REPO@@</b></p>
  <p>Entrega original: 14 de outubro de 2026</p>
  <p><b>Versão atualizada para o Pacote 4</b> (ver &quot;O que mudou&quot;)</p>
</div>

<h1>O que mudou desde a entrega de 14/10</h1>
<p>Este documento reproduz o Pacote 1 entregue (o arquivo original foi mantido como <code>PulsePass_Pacote1_entregue.pdf</code>) e acrescenta, em destaque, o que a construção do produto mudou ou confirmou. O texto das seções 1 a 5 é o entregue; o que é novo aparece nas colunas &quot;Status&quot;, &quot;Estado&quot; e &quot;Usado de fato&quot;, nas notas sobre as telas e nesta seção.</p>
<ul>
  <li><b>Backlog:</b> todas as histórias detalhadas (US01 a US29 e US31 a US33) foram atendidas; US30 e US34 a US39 seguem como candidatas de R3, não detalhadas e fora do produto. O status e a evidência de cada uma estão no documento do Pacote 4.</li>
  <li><b>Requisitos não funcionais:</b> a seção 4 traz o estado final de cada um (a maioria atendida; RNF15, RNF20 e RNF22 parciais, com o motivo no documento do Pacote 4).</li>
  <li><b>Regras esclarecidas durante a construção:</b> (1) o pedido nasce CRIADO e vira RESERVADO quando a reserva é confirmada pelo evento do Inventário; (2) pagamento recusado ou falha do gateway devolvem o pedido a RESERVADO, para nova tentativa enquanto a reserva estiver ativa; (3) a capacidade de um lote é controlada por contadores (<code>qtd_reservada</code> e <code>qtd_vendida</code>); (4) a expiração da reserva fica suspensa por até 30 s durante o pagamento; (5) com a fila virtual ligada, uma admissão vale uma reserva e dura 5 minutos; (6) o e-mail do comprador só viaja no evento do pagamento e é apagado da Emissão depois do envio.</li>
  <li><b>Processo com IA (SDD):</b> toda funcionalidade tem spec em <code>/specs</code>, escrita antes do código (seção 1.4). As regras dos agentes estão versionadas em <code>.claude/CLAUDE.md</code>. O código foi gerado com apoio do Claude Code e conferido por testes unitários, de integração e de mutação e pelo smoke contra a stack real.</li>
  <li><b>Ferramentas:</b> a tabela da seção 1.2 mostra, para cada finalidade, o que foi previsto e o que foi de fato usado. O quadro do GitHub Projects, as <i>issues</i> e o Figma não foram usados; a proteção de branch e a revisão cruzada no GitHub continuam abertas (Pacote 4, seção 7).</li>
  <li><b>Mockups:</b> a SPA seguiu a estrutura e o mapa de navegação da seção 5; as diferenças estão na tabela da seção 5.4.</li>
</ul>

<div class="section"><h1>1. Processo de Desenvolvimento Suportado por IA</h1>
<p>O grupo adota um processo iterativo e incremental baseado em Spec-Driven Development (SDD): toda funcionalidade nasce de uma especificação escrita e revisada por humanos, que vira o contrato para os agentes de IA gerarem código, testes e documentação. A IA acelera a execução; a decisão (o que construir, aceitar ou rejeitar) permanece com o time. O ciclo é organizado em sprints de duas semanas alinhadas aos quatro pacotes de entrega da disciplina.</p>
<h2>1.1 Ciclo de desenvolvimento</h2>
<div class="fig sm"><script type="text/mermaid">@@CICLO@@</script>
<div class="cap">Laranja = etapa com decisão humana obrigatória (human-in-the-loop). Azul = etapa executada majoritariamente por agentes, sempre sobre a spec aprovada.</div></div>
<ol>
@@PASSOS@@
</ol>
<h2>1.2 Ferramentas</h2>
@@FERR@@
<h2>1.3 Agentes e responsabilidades</h2>
@@AGENTES@@
<h2>1.4 Estrutura das specs no repositório</h2>
<p>Estrutura prevista no Pacote 1 e, abaixo, a que existe no repositório (uma pasta por funcionalidade):</p>
@@SPECS@@
<div class="box"><b>Regras do time para uso de IA.</b> (1) Nenhum código entra em develop sem revisão humana. (2) Prompts e regras dos agentes são versionados no repositório. (3) Dados pessoais reais e segredos nunca vão para prompts. (4) Todo PR indica quais partes foram geradas por IA. (5) Definition of Done: spec atualizada, testes passando no CI, cobertura mantida, PR revisado e documentação ajustada.</div>
</div>

<div class="section"><h1>2. Documento de Visão do Produto</h1>
<h2>2.1 Problema</h2>
<p>Na abertura de vendas de megaeventos (shows, finais esportivas, festivais), milhares de compradores acessam a plataforma no mesmo segundo. Sistemas baseados apenas em HTTP síncrono esgotam conexões de banco, ficam lentos ou fora do ar e, no pior caso, vendem o mesmo assento duas vezes. Para o comprador isso gera frustração e desconfiança; para o organizador, prejuízo financeiro, reembolsos e dano à marca.</p>
<h2>2.2 Declaração de posicionamento</h2>
<div class="box">Para organizadores de grandes eventos e seus compradores que precisam vender e controlar o acesso a milhares de ingressos em poucos minutos, o PulsePass é uma plataforma distribuída de bilhetagem que garante zero sobre-venda, reserva temporária justa de 10 minutos e emissão segura de ingressos mesmo sob rajadas de tráfego. Diferente de sistemas monolíticos síncronos, nosso produto usa microsserviços orientados a eventos (Kafka), bloqueio atômico de assentos e checkout idempotente, mantendo a experiência estável no pico.</div>
<h2>2.3 Stakeholders e usuários</h2>
@@STAKE@@
<h2>2.4 Funcionalidades principais (por módulo)</h2>
@@MODULOS@@
<h2>2.5 Escopo</h2>
<p><b>Dentro do escopo:</b> venda de ingressos com e sem assento marcado, reserva com expiração, pagamento via gateway simulado, emissão e validação de ingresso, painéis do organizador e operacional.</p>
<p><b>Fora do escopo:</b> integração com gateways de pagamento reais, revenda/transferência entre usuários, aplicativo móvel nativo (o front-end será SPA responsiva), meia-entrada com validação documental e emissão fiscal.</p>
<h2>2.6 Métricas de sucesso</h2>
@@METRICAS@@
<h2>2.7 Premissas e restrições</h2>
<ul>
  <li>Arquitetura em microsserviços; Keycloak (ou Cognito) para identidade; Kafka para streaming; PostgreSQL e Redis para persistência e bloqueios.</li>
  <li>Ambiente completo executável localmente via docker-compose; CI no GitHub Actions.</li>
  <li>Grupo de 3 a 5 pessoas, prazo de um semestre, entregas em quatro pacotes.</li>
  <li>Pagamento é simulado: o gateway responde com aprovação, recusa ou atraso configuráveis para testar resiliência.</li>
</ul>
</div>

<div class="section"><h1>3. Backlog: Épicos, Histórias e Story Map</h1>
<h2>3.1 Épicos</h2>
@@EPICOS@@
<h2>3.2 Story Map</h2>
<p>Leitura: a linha superior é o backbone (jornada do usuário, da esquerda para a direita); abaixo, as histórias de cada etapa, divididas horizontalmente por release. R1 = MVP (fluxo fim a fim mínimo), R2 = robustez e experiência, R3 = operação e analytics.</p>
<div class="fig"><img src="img/p1-001.png" alt="Story Map"><div class="cap">Figura 1 – Story Map do PulsePass (como entregue em 14/10).</div></div>
<h2>3.3 Histórias de usuário</h2>
@@HIST@@
<p>Histórias de R3 não detalhadas acima (US30, US34 a US39) permanecem no backlog como candidatas; não foram implementadas.</p>
<h2>3.4 Critérios de aceite das histórias críticas</h2>
@@GHERKIN@@
</div>

<div class="section"><h1>4. Requisitos Não-Funcionais</h1>
<p>Os requisitos abaixo são mensuráveis e foram verificados por testes automatizados, testes de carga ou inspeção no CI. Valores de carga referem-se ao ambiente local em Docker Compose e servem como meta do projeto acadêmico. A coluna &quot;Estado&quot; é nova; a evidência de cada um está no documento do Pacote 4.</p>
@@RNF@@
</div>

<div class="section"><h1>5. Mockups e Mapa de Navegação do Front-end</h1>
<p>Mockups em média fidelidade (wireframes) da SPA, como entregues em 14/10. Cores e tipografia eram provisórias; o foco é estrutura, conteúdo e fluxo. Cada tela indica as histórias que atende.</p>
<h2>5.1 Mapa de navegação</h2>
<div class="fig"><img src="img/p1-002.png" alt="Mapa de navegação"><div class="cap">Figura 2 – Mapa de navegação. Azul escuro = ponto de entrada de cada papel após o login. Laranja = tela com tempo limitado. Verde = telas de ingresso. Linhas tracejadas = desvios do fluxo principal.</div></div>
<h2>5.2 Telas do cliente</h2>
<div class="fig mock"><img src="img/p1-003.png" alt="Telas 1 e 2"><div class="cap">Tela 1 — Home/Catálogo (US11, US13) · Tela 2 — Detalhe do evento (US12).</div></div>
<div class="fig mock"><img src="img/p1-004.png" alt="Telas 3 e 4"><div class="cap">Tela 3 — Mapa de assentos (US14, US15) · Tela 4 — Checkout (US17, US19 a US21).</div></div>
<div class="fig mock"><img src="img/p1-005.png" alt="Telas 5 e 6"><div class="cap">Tela 5 — Status do pedido (US23, US27) · Tela 6 — Meus ingressos (US24 a US26).</div></div>
<h2>5.3 Telas do organizador, operador e administrador</h2>
<div class="fig mock"><img src="img/p1-006.png" alt="Telas 7 e 8"><div class="cap">Tela 7 — Painel do organizador (US07 a US10, US31, US32) · Tela 8 — Leitor de QR do operador (US28, US29).</div></div>
<div class="fig mock"><img src="img/p1-007.png" alt="Telas 9 e 10"><div class="cap">Tela 9 — Cadastro de evento em etapas (US07 a US09) · Tela 10 — Administração (US04 a US06, US33).</div></div>
<h2>5.4 Do mockup à SPA implementada</h2>
@@TELAS@@
</div>
</body></html>'''

mermaid_js = open('src/pacote2.html', encoding='utf8').read()
script = mermaid_js[mermaid_js.find('<script type="module">'):mermaid_js.rfind('</script>') + len('</script>')]
repl = {
    '@@CSS@@': css, '@@REPO@@': repo, '@@CICLO@@': ciclo,
    '@@PASSOS@@': '\n'.join('  <li><b>%s</b> %s</li>' % p for p in passos),
    '@@FERR@@': tabela(['Finalidade', 'Ferramenta prevista', 'Uso no projeto', 'Usado de fato'], ferramentas, ['16%', '22%', '28%', '34%']),
    '@@AGENTES@@': tabela(['Agente', 'Entrada → Saída', 'Guarda-corpos'], agentes, ['14%', '40%', '46%']),
    '@@SPECS@@': specs,
    '@@STAKE@@': tabela(['Perfil', 'Descrição', 'Principais necessidades'], stake, ['16%', '38%', '46%']),
    '@@MODULOS@@': tabela(['Módulo', 'Capacidades'], modulos, ['24%', '76%']),
    '@@METRICAS@@': tabela(['Métrica de sucesso', 'Resultado'], metricas, ['50%', '50%']),
    '@@EPICOS@@': tabela(['ID', 'Épico', 'Objetivo', 'Módulo'], epicos, ['8%', '24%', '52%', '16%']),
    '@@HIST@@': tabela(['ID', 'Épico', 'História', 'Rel.', 'Status'], hist_linhas, ['7%', '7%', '63%', '6%', '17%']),
    '@@GHERKIN@@': ''.join('<h3>%s</h3><div class="gh">%s</div>' % (t, ''.join('<p>%s</p>' % l for l in ls)) for t, ls in gherkin),
    '@@RNF@@': tabela(['ID', 'Categoria', 'Requisito', 'Critério de verificação', 'Estado'], rnf_linhas, ['7%', '11%', '39%', '28%', '15%']),
    '@@TELAS@@': tabela(['Tela desenhada', 'Histórias', 'Como ficou na SPA'], telas, ['24%', '16%', '60%']),
}
for k, v in repl.items():
    html = html.replace(k, v)
html = html.replace('</body></html>', script + '\n</body></html>')
open('src/pacote1_atualizado.html', 'w', encoding='utf8').write(html)
print('ok', len(html))
