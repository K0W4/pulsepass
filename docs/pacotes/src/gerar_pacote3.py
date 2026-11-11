"""Gera src/pacote3.html e src/pacote2_atualizado.html. Uso: python3 src/gerar_pacote3.py REPO_URL [RESULTADO_CI]"""
import re, sys
repo = sys.argv[1] if len(sys.argv) > 1 else 'REPO_URL'
ci = sys.argv[2] if len(sys.argv) > 2 else 'resultado a registrar após a execução no GitHub'
css = re.search(r'<style>(.*?)</style>', open('src/pacote2.html', encoding='utf8').read(), re.S).group(1).replace('PulsePass · Pacote 2 · ', 'PulsePass · Pacote 3 · ')

def tabela(cab, linhas, larg=None):
    th = ''.join('<th%s>%s</th>' % ((' style="width:%s"' % larg[i]) if larg else '', c) for i, c in enumerate(cab))
    tr = ''.join('<tr>' + ''.join('<td>%s</td>' % c for c in l) + '</tr>' for l in linhas)
    return '<table><thead><tr>%s</tr></thead>%s</table>' % (th, tr)

OK, PARC, PEND = 'Atendido', 'Parcial', 'Pendente'
us = [
 ('US01', 'Criar conta com e-mail e senha', OK, 'Auto-cadastro pela tela de registro do Keycloak, com o papel cliente por padrão (<code>smoke.sh</code>).'),
 ('US02', 'Entrar e receber token de acesso', OK, '<code>smoke.sh</code> e login pela SPA (OIDC com PKCE).'),
 ('US04', 'Papel adequado em cada rota (RBAC)', OK, '401 e 403 testados em todos os serviços (<code>ConsumidorEApiIT</code>, <code>CheckoutIT</code>, <code>EmissaoIT</code>, <code>RotasTest</code>) e no smoke; a SPA esconde o que o papel não permite.'),
 ('US07', 'Cadastrar evento', OK, '<code>ConsumidorEApiIT</code>, <code>smoke.sh</code> e formulário do organizador na SPA.'),
 ('US08', 'Criar lotes com preço, quantidade e período', OK, '<code>Fixtures</code> nos testes, <code>smoke.sh</code> e formulário na SPA.'),
 ('US09', 'Setores e assentos numerados (R2)', OK, 'Setor marcado cria os assentos; formulário na SPA; usado em todos os testes de reserva.'),
 ('US11', 'Listar eventos', OK, 'Catálogo público com cache Redis; SPA e <code>smoke.sh</code>.'),
 ('US12', 'Detalhes, lotes e disponibilidade', OK, '<code>GET /eventos/{id}</code>; tela de detalhe da SPA.'),
 ('US14', 'Mapa de assentos livre, reservado e vendido', PARC, 'Mapa na SPA, com alternativa em lista; a disponibilidade é atualizada por <i>polling</i> a cada 5 s. Falta o WebSocket (realtime-service).'),
 ('US15', 'Reserva atômica por 10 minutos', OK, '<code>ReservaConcorrenteIT</code>: 500 reservas simultâneas, 1 sucesso e 499 conflitos.'),
 ('US16', 'Liberar reservas não pagas', OK, '<code>ExpiracaoEOutboxIT</code>.'),
 ('US17', 'Cancelar reserva (R2)', OK, '<code>ConsumidorEApiIT.clienteCancelaAReserva...</code>; botão na tela de checkout.'),
 ('US19', 'Revisar pedido com itens e total', OK, '<code>GET /pedidos/{id}</code>; tela de checkout com cronômetro.'),
 ('US20', 'Pagar o pedido', OK, '<code>CheckoutIT</code>, <code>smoke.sh</code> e SPA (verificado no navegador).'),
 ('US21', 'Clique repetido não cobra duas vezes', OK, '<code>CheckoutIT</code>: 1 chamada ao gateway; 20 chamadas concorrentes, 1 pagamento. A SPA reutiliza a mesma chave nas repetições.'),
 ('US23', 'Pagamento recusado e nova tentativa (R2)', OK, '<code>CheckoutIT</code>: recusa volta a RESERVADO e a segunda tentativa paga; a SPA gera chave nova após a recusa.'),
 ('US24', 'Ingresso em PDF com QR Code', OK, '<code>EmissaoIT</code> (PDF lido pelo PDFBox), <code>smoke.sh</code> e SPA.'),
 ('US26', 'Ver todos os meus ingressos', OK, '<code>GET /ingressos</code> e tela "Meus ingressos" com QR.'),
 ('US28', 'Operador lê o QR e vê se é válido', OK, '<code>EmissaoIT</code>, <code>smoke.sh</code>; a tela da portaria recebe o código digitado (sem leitura por câmera).'),
 ('US29', 'Ingresso usado é recusado (R2)', OK, 'Resposta 409 com o horário da primeira leitura; 50 leituras simultâneas liberam 1.'),
 ('US31', 'Total vendido e ocupação por lote', OK, '<code>GET /eventos/{id}/vendas</code>, só para o dono do evento (<code>ConsumidorEApiIT</code>); painel na SPA.'),
 ('US03, US05, US06, US10, US13, US18, US22, US25, US27, US30, US32 a US39', 'Renovação automática da sessão, cadastro de organizador, auditoria, publicar evento, busca, fila virtual, publicação por Outbox como história própria, e-mail, tempo real, painéis', PEND, 'Fora do escopo do checkpoint. US22 está atendida na prática (Outbox em todos os serviços), mas sem história própria no código.'),
]
rnf = [
 ('RNF01', 'Zero sobre-venda', OK, '<code>ReservaConcorrenteIT</code> (assento e lote); restrição <code>check</code> no banco como última barreira.'),
 ('RNF02', 'Expiração em 10 min + 30 s', OK, '<code>ExpiracaoEOutboxIT</code> (TTL reduzido a 2 s no teste); suspensão limitada a 30 s.'),
 ('RNF03', 'Pagamento idempotente', OK, '<code>CheckoutIT</code>.'),
 ('RNF04', 'Transactional Outbox', OK, '<code>ExpiracaoEOutboxIT</code>: com o Kafka pausado o evento fica pendente e sai quando ele volta.'),
 ('RNF05', 'Consumo idempotente', OK, 'Duplicata de <code>PedidoPago</code>, <code>ReservaCriada</code> e <code>ReservaExpirada</code> testada nos três serviços.'),
 ('RNF06', 'Catálogo p95 ≤ 200 ms a 1.000 req/s', OK, 'k6: p95 = 71 ms a ~1.000 req/s por 30 s, 0% de falhas (<code>docs/evidencias</code>). Medido num laptop, sem latência de rede real.'),
 ('RNF07', 'Reserva p95 ≤ 500 ms a 200/s', OK, 'k6: p95 = 7,4 ms a ~200 req/s por 30 s; 6.000 reservas criadas, 0% de falhas (mesmo ambiente).'),
 ('RNF08', 'Ingresso em até 30 s (p95)', PARC, 'Sem telemetria-service. Os traces no Jaeger mostram o tempo entre <code>PedidoPago</code> e <code>IngressoEmitido</code>, mas o p95 não foi medido; no smoke o ingresso aparece em poucos segundos.'),
 ('RNF09', 'Réplicas sem erro funcional', OK, '<code>replicas.sh</code>: 2 réplicas de cada serviço, grupos Kafka com 2 membros, smoke completo e 8 instâncias monitoradas.'),
 ('RNF10', 'Timeout, retry e circuit breaker', OK, '<code>CheckoutIT</code>: 3 tentativas com backoff, 503, pedido volta a RESERVADO.'),
 ('RNF11', 'Emissão fora não impede a venda', OK, '<code>resiliencia.sh</code>: com a Emissão parada o pagamento é aprovado; ao voltar, ela emite o ingresso retido no Kafka. O gateway responde 503 (testado em <code>RotasTest</code>).'),
 ('RNF12', 'JWT stateless (15 min, refresh 7 dias)', PARC, '401 sem token testado; tempos configurados no realm, mas a renovação não foi testada.'),
 ('RNF13', 'RBAC em todas as rotas', OK, 'Testes por papel nos serviços e no gateway.'),
 ('RNF14', 'QR assinado', OK, '<code>DominioTest</code> e <code>EmissaoIT</code>: adulterado ou de outro segredo é rejeitado.'),
 ('RNF15', 'LGPD', PARC, 'Senhas só no Keycloak; os serviços guardam apenas o identificador do usuário; sem revisão formal de logs.'),
 ('RNF16', 'Observabilidade', OK, 'Micrometer Tracing, OpenTelemetry Collector e Jaeger; Prometheus e Grafana com dashboard; logs JSON com o trace id (o correlation-id). Um único trace atravessa gateway, Checkout, Emissão e Inventário, inclusive pelo Outbox e pelo Kafka (<code>smoke.sh</code>).'),
 ('RNF17', 'Cobertura > 70% e Testcontainers', OK, 'Domínio com 100%, 100% e 93% de linhas; integração com PostgreSQL, Redis e Kafka reais.'),
 ('RNF18', 'Lint e arquitetura documentada', OK, 'Spotless (google-java-format) falha o build; C4 e ADRs no Pacote 2.'),
 ('RNF19', 'Ambiente com um comando', OK, '<code>docker compose up -d --wait</code> sobe 15 contêineres saudáveis (mais o de inicialização dos tópicos); verificado do zero nesta máquina.'),
 ('RNF20', 'CI bloqueia PR vermelho', PARC, 'Workflow executa no GitHub: ' + ci + '. A proteção das branches (checks obrigatórios) ainda não foi configurada, então o bloqueio de PR não está ativo.'),
 ('RNF21', 'Compra em até 5 telas', OK, 'Detalhe, assentos, checkout e meus ingressos (4 telas), percorridas no navegador.'),
 ('RNF22', 'Acessibilidade WCAG AA', OK, 'Lighthouse 100 nas páginas públicas (celular e desktop); axe-core sem violações WCAG A/AA em todas as telas, com os três papéis. Mapa de assentos com alternativa em lista. A auditoria é manual (não roda na CI).'),
 ('RNF23', 'Fila virtual por ordem de chegada', PEND, 'Não implementada.'),
]
div = [
 ('2.3.2 Checkout', '<code>IdempotencyFilter</code> como filtro servlet', 'A lógica está em <code>PagamentoService</code> (TX1, gateway fora de transação, TX2). Mesmas regras; chamada concorrente com a mesma chave enquanto a primeira roda recebe 409.'),
 ('2.3.2 Checkout', 'Falha do gateway', 'Responde 503; o pagamento falho passa a ter chave própria (<code>#falha:id</code>) para a chave do cliente poder ser reutilizada. Recusa responde 402.'),
 ('2.3.3 Emissão', '<code>PdfStorage</code> e <code>NotificacaoWorker</code>/<code>EmailSender</code>', 'O PDF é gerado sob demanda, sem armazenamento (serviço continua sem estado). E-mail e a tabela <code>NOTIFICACAO</code> ficam para o Pacote 4 (US25 é R2).'),
 ('2.2 Contêineres', 'SPA', 'Implementada em TypeScript (Vite) e servida por Nginx, que também faz proxy de <code>/api</code> para o gateway. Atualiza a disponibilidade por <i>polling</i> de 5 s, e não por WebSocket.'),
 ('2.2 Contêineres', 'iam-service, telemetria-service e realtime-service', 'Não implementados. Os papéis vêm direto do Keycloak (<code>realm_access.roles</code>), e cada serviço valida o JWT pelo JWKS. O cadastro de clientes é o auto-cadastro do Keycloak, com o papel cliente por padrão.'),
 ('2.2 Contêineres', 'API Gateway com rate limit', 'O gateway valida JWT e roteia sob o prefixo <code>/api</code>; responde 503 quando o serviço de destino está fora do ar. O rate limit não foi implementado.'),
 ('2.3.1 Inventário', '<code>FilaController</code> e fila virtual', 'Não implementados. O Redis é usado só para o cache do catálogo e para o <code>SeatLock</code>.'),
 ('3 Dados', 'Tabela de Outbox', '<code>outbox_event</code> guarda <code>topic</code>, <code>msg_key</code>, o envelope completo em <code>body</code> e o <code>traceparent</code>. A reserva ganhou <code>suspensa_ate</code> (ADR-008) e o lote tem <code>check</code> contra sobre-venda. O ingresso guarda <code>evento_nome</code> e <code>descricao</code> copiados do pedido.'),
 ('4 Kafka', 'Criação de tópicos', 'Um contêiner <code>kafka-init</code> cria os tópicos com as partições da seção 4.2 e a criação automática está desligada. Sem isso, o consumidor que subia primeiro fazia o Kafka criar o tópico com 1 partição (defeito encontrado no smoke). Existem os tópicos de reserva, assento, pedido, ingresso e três DLQs; os de IAM e telemetria não foram criados.'),
 ('4 Kafka', 'DLQ', 'Três tentativas com intervalo de 500 ms e depois o tópico <code>&lt;tópico&gt;.&lt;grupo&gt;.dlq</code>, na biblioteca <code>commons</code>.'),
 ('1.1 e 5.3 Observabilidade', 'OpenTelemetry, Prometheus e Grafana no Compose', 'Implementado: Micrometer Tracing com exportação OTLP para o Collector e o Jaeger; Prometheus com descoberta por DNS (encontra todas as réplicas); Grafana com dashboard provisionado; logs JSON. O <code>traceparent</code> é gravado na linha do Outbox e reaplicado pelo relay, para o trace atravessar a publicação assíncrona.'),
 ('5 Pipeline', 'Etapas', 'Workflow <code>.github/workflows/ci.yml</code> com lint, testes, front-end, smoke, varredura de segredos, PIT (perfil Maven <code>mutacao</code>, no push em develop e à noite) e publicação no GHCR. O Compose não tem Mailpit.'),
 ('1.1 Stack', 'Versões', 'Spring Boot 3.5.16, Spring Cloud 2025.0.3, Resilience4j 2.4.0 (uso programático), PDFBox 3.0.8, ZXing 3.5.4, Testcontainers 2.x, Keycloak 26.0, Vite 6, TypeScript 5.9, keycloak-js 26, Jaeger 1.62, Prometheus 2.55, Grafana 11.3.'),
]
total = (sum(1 for r in rnf if r[2] == OK), sum(1 for r in rnf if r[2] == PARC), sum(1 for r in rnf if r[2] == PEND))
html = '''<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>PulsePass – Pacote 3</title><style>%s</style></head><body>
<div class="cover">
  <p>PONTIFÍCIA UNIVERSIDADE CATÓLICA DO RIO GRANDE DO SUL<br>ESCOLA POLITÉCNICA<br>46504-04 · Construção de Software · Turma 330 · 2026/2<br>Prof. Michael da Costa Mora</p>
  <p style="margin-top:14mm"><small>PROJETO INTEGRADOR · PACOTE 3</small></p>
  <h1>PulsePass</h1>
  <h2>Checkpoint do Desenvolvimento</h2>
  <p>Estado do código, documentação atualizada dos Pacotes 1 e 2 e evidências de teste</p>
  <p style="margin-top:22mm">Integrantes: Gabriel Kowaleski e Mayra Bordin</p>
  <p>Repositório: <b>%s</b></p>
  <p>Entrega: 23 de novembro de 2026</p>
</div>

<h1>1. Resumo do checkpoint</h1>
<p>O repositório contém a fatia vertical <b>R1</b> do PulsePass: do catálogo de eventos até o ingresso validado na portaria, passando por reserva atômica, pagamento idempotente, Outbox e Kafka, agora com a <b>SPA</b>, observabilidade, testes de carga, réplicas e teste de resiliência. Tudo sobe com <code>docker compose up -d --wait</code> e o script <code>scripts/smoke.sh</code> percorre o fluxo completo pelo gateway, autenticando no Keycloak (inclusive o auto-cadastro).</p>
<table><thead><tr><th style="width:30%%">Indicador</th><th>Resultado</th></tr></thead>
<tr><td>Testes automatizados</td><td><b>62</b>, todos passando: 21 unitários, 25 de integração com PostgreSQL, Redis e Kafka reais (Testcontainers), 3 do simulador, 5 do gateway e 8 do front-end.</td></tr>
<tr><td>Cobertura de linhas do domínio</td><td>Inventário 100%%, Checkout 100%%, Emissão 93%% (meta: 70%%, verificada no build).</td></tr>
<tr><td>Teste de mutação (PIT)</td><td>100%% dos mutantes mortos nos três domínios (meta: 60%%).</td></tr>
<tr><td>Smoke ponta a ponta</td><td>Passa contra a stack real, incluindo auto-cadastro, RBAC, idempotência, ingresso em PDF, validação do QR, trace único e dashboard.</td></tr>
<tr><td>Carga (k6)</td><td>Catálogo: p95 = 71 ms a ~1.000 req/s. Reserva: p95 = 7,4 ms a ~200 req/s, 6.000 reservas, 0%% de falhas.</td></tr>
<tr><td>Réplicas e resiliência</td><td>2 réplicas de cada serviço de negócio com o fluxo completo passando; Emissão derrubada durante uma compra sem perder o ingresso.</td></tr>
<tr><td>Acessibilidade</td><td>Lighthouse 100 nas páginas públicas; axe-core sem violações WCAG A/AA em todas as telas.</td></tr>
<tr><td>Histórias de R1 e R2</td><td>20 de 21 atendidas; US14 parcial (sem WebSocket).</td></tr>
<tr><td>Requisitos não funcionais</td><td>%d atendidos, %d parciais, %d pendentes (seção 4).</td></tr></table>
<div class="box"><b>Limites honestos.</b> As medições de carga foram feitas num laptop, sem latência de rede real; servem para verificar as metas, não como benchmark. A cobertura de 70%% mede o pacote <code>domain</code> (máquinas de estado e regras puras); a orquestração dos serviços é exercida pelos testes de integração, mas não entra nesse percentual. O axe-core e o Lighthouse foram executados à mão, não na CI. As proteções de branch do GitHub não foram configuradas. A leitura do QR na portaria é por digitação do código.</div>

<h2>1.1 Como reproduzir</h2>
<pre>cp .env.example .env
docker compose up -d --build --wait     # SPA em http://localhost:3000
./scripts/smoke.sh
./scripts/replicas.sh                   # 2 réplicas de cada serviço
./scripts/resiliencia.sh                # derruba a Emissão durante uma compra
./scripts/carga.sh                      # k6: catálogo a 1.000 req/s e reserva a 200 req/s
./mvnw verify                           # unitários + integração + formatação + cobertura do domínio
(cd frontend &amp;&amp; npm ci &amp;&amp; npm test &amp;&amp; npm run build)
docker compose down -v</pre>
<p>Pré-requisitos: Docker com Compose e Java 21 (o Maven vem pelo wrapper). O <code>README.md</code> do repositório traz os detalhes, as portas e os testes que sustentam cada requisito.</p>

<div class="section"><h1>2. Estado do backlog (Pacote 1)</h1>
<p>Status das histórias da release R1 e das de R2 que foram entregues junto, com a evidência de cada uma. As histórias não citadas individualmente estão no último grupo.</p>
%s
</div>

<div class="section"><h1>3. Divergências em relação ao Pacote 2</h1>
<p>O desenho do Pacote 2 foi seguido, com as diferenças abaixo. Todas estão registradas aqui e, quando afetam contratos, no documento atualizado do Pacote 2 (seção 8).</p>
%s
</div>

<div class="section"><h1>4. Requisitos não funcionais: estado e evidência</h1>
%s
</div>

<div class="section"><h1>5. Atualizações do Pacote 1</h1>
<p>O Pacote 1 entregue continua válido. As atualizações são estas:</p>
<ul>
  <li><b>Backlog:</b> o status por história está na seção 2. Nenhuma história foi removida; as de R2 e R3 não citadas seguem como candidatas.</li>
  <li><b>Regras esclarecidas durante a construção:</b> (1) o pedido nasce CRIADO e vira RESERVADO quando a reserva é confirmada pelo evento do Inventário; (2) pagamento recusado ou falha do gateway devolvem o pedido a RESERVADO, para nova tentativa enquanto a reserva estiver ativa; (3) a capacidade de um lote é controlada por contadores (<code>qtd_reservada</code> e <code>qtd_vendida</code>); (4) a expiração da reserva fica suspensa por até 30 s durante o pagamento.</li>
  <li><b>Processo com IA (SDD):</b> as três funcionalidades críticas têm spec em <code>/specs</code> (reserva atômica, pagamento idempotente, ingresso com QR), escritas antes do código. As regras dos agentes estão versionadas em <code>.claude/CLAUDE.md</code>. O código foi gerado com apoio do Claude Code e revisado pelo grupo; os testes de integração, o smoke e a verificação da SPA no navegador serviram de contrapeso e acharam defeitos reais, descritos abaixo.</li>
  <li><b>Ferramentas efetivamente usadas:</b> Java 21, Spring Boot 3.5, Maven, Testcontainers, PIT, k6, GitHub Actions, Docker Compose, Keycloak, OpenTelemetry, Prometheus, Grafana, Jaeger, Vite e TypeScript. Figma, GitHub Projects e Structurizr continuam previstos.</li>
  <li><b>Front-end (Pacote 1, seção 5):</b> a SPA foi implementada seguindo a estrutura e o mapa de navegação dos mockups: catálogo, detalhe, mapa de assentos, checkout com contagem regressiva, meus ingressos, painel e cadastro do organizador e leitor da portaria. O status do pedido em tempo real (tela 5) é feito dentro do checkout, por <i>polling</i>; a tela de administração (tela 10) e a leitura do QR por câmera ficam para o Pacote 4.</li>
</ul>
<h2>5.1 Defeitos achados pelos testes e corrigidos</h2>
<ul>
  <li><b>Chave de idempotência reutilizada após falha do gateway:</b> o pagamento falho mantinha a chave e a nova tentativa violava a unicidade (achado em <code>CheckoutIT</code>).</li>
  <li><b>Erros 409, 422 e 404 virando 403:</b> o redirecionamento para <code>/error</code> caía na regra de negação do Spring Security. Os testes com MockMvc não veem isso; o smoke contra o Compose real encontrou.</li>
  <li><b>Tópico criado com 1 partição:</b> corrida de criação de tópicos entre serviços; corrigida com o contêiner <code>kafka-init</code> e criação automática desligada.</li>
  <li><b>Limite do segredo do QR:</b> mutante sobrevivente (32 contra 31 caracteres) fechado com teste de fronteira.</li>
  <li><b>Papel padrão sem efeito no auto-cadastro:</b> o Keycloak só importa os papéis compostos pela lista de papéis do realm; quem se cadastrava não recebia o papel cliente. Corrigido e coberto pelo smoke.</li>
  <li><b>Gateway devolvia 500 com o serviço fora do ar:</b> agora responde 503 (teste em <code>RotasTest</code>; achado pelo teste de resiliência).</li>
  <li><b>Trace partido pelo Outbox:</b> a publicação assíncrona perdia o contexto; o <code>traceparent</code> passou a ser gravado na linha do Outbox e reaplicado pelo relay.</li>
  <li><b>Ordem dos assentos e dos ingressos na SPA:</b> a ordem alfabética (A1, A10, A11, A2) foi trocada por ordem natural, e os ingressos mais novos aparecem primeiro.</li>
</ul>
</div>

<div class="section"><h1>6. Atualizações do Pacote 2</h1>
<p>O documento do Pacote 2 foi regenerado como <code>PulsePass_Pacote2_atualizado.pdf</code>, com a nova seção 8 (atualizações do Pacote 3) e a tabela de divergências da seção 3 deste documento. O arquivo originalmente entregue foi mantido como <code>PulsePass_Pacote2_entregue.pdf</code>.</p>
<p>O Pacote 1 original não tem fonte editável neste repositório, por isso ele é mantido como entregue (<code>PulsePass_Pacote1_entregue.pdf</code>) e suas atualizações estão na seção 5 deste documento.</p>

<h1>7. Próximos passos (Pacote 4)</h1>
<ul>
  <li><code>iam-service</code>, <code>telemetria-service</code> e <code>realtime-service</code> (WebSocket no lugar do <i>polling</i>); medição do p95 do RNF08.</li>
  <li>E-mail com Mailpit; fila virtual (RNF23); tela de administração e leitura de QR por câmera.</li>
  <li>Rate limit no gateway; proteção das branches e revisão cruzada no GitHub; auditoria de acessibilidade automatizada na CI.</li>
  <li>Publicação de eventos (US10) e renovação automática da sessão testada (RNF12).</li>
</ul></div>
</body></html>''' % (css, repo, total[0], total[1], total[2],
                   tabela(['História', 'Descrição', 'Status', 'Evidência'], us, ['9%', '27%', '10%', '54%']),
                   tabela(['Seção do Pacote 2', 'Desenhado', 'Como ficou'], div, ['16%', '27%', '57%']),
                   tabela(['RNF', 'Requisito', 'Estado', 'Evidência'], rnf, ['8%', '24%', '10%', '58%']))
open('src/pacote3.html', 'w', encoding='utf8').write(html)

# Pacote 2 atualizado: seção 8 com a tabela de divergências nova
h = open('src/pacote2.html', encoding='utf8').read()
def rep(o, n):
    global h
    assert o in h, o[:60]; h = h.replace(o, n, 1)
rep('<title>PulsePass – Pacote 2</title>', '<title>PulsePass – Pacote 2 (atualizado)</title>')
rep('  <p>Entrega: 4 de novembro de 2026</p>', '  <p>Entrega original: 4 de novembro de 2026</p>\n  <p><b>Versão atualizada para o Pacote 3</b> (ver seção 8)</p>')
rep('  <li><b>Rastreabilidade com o enunciado</b></li>', '  <li><b>Rastreabilidade com o enunciado</b></li>\n  <li><b>Atualizações do Pacote 3</b> – o que mudou entre o desenho e a implementação</li>')
sec = '''<div class="section">
<h1>8. Atualizações do Pacote 3</h1>
<p>Este documento foi entregue em 04/11/2026 como desenho. O Pacote 3 implementou a fatia vertical R1 (catálogo, reserva atômica, pedido, pagamento idempotente, ingresso e validação), a SPA e a observabilidade, e o desenho foi seguido com as diferenças abaixo. As seções 1 a 7 permanecem como o desenho de referência; esta seção registra o que mudou ao construir.</p>
<p><b>Implementado e verificado:</b> ADR-001 a ADR-004, ADR-007 e ADR-008; Outbox com <code>FOR UPDATE SKIP LOCKED</code> e consumo idempotente (<code>evento_processado</code>); máquinas de estado de reserva, assento, pedido, pagamento e ingresso; reserva com <code>UPDATE</code> condicional e filtro Redis; pagamento com <code>Idempotency-Key</code>, retry e circuit breaker; QR assinado; SPA; OpenTelemetry, Prometheus e Grafana; pipeline, Compose e processo de GCS descritos nas seções 5 e 6.</p>
<p><b>Ainda não implementado (Pacote 4):</b> iam-service, telemetria-service, realtime-service, e-mail, fila virtual e rate limit no gateway. A tabela abaixo detalha as diferenças; o estado de cada história e de cada requisito não funcional, com a evidência de teste, está no documento do Pacote 3.</p>
''' + tabela(['Seção do Pacote 2', 'Desenhado', 'Como ficou'], div, ['16%', '27%', '57%']) + '''
</div>

'''
rep('<script type="module">', sec + '<script type="module">')
open('src/pacote2_atualizado.html', 'w', encoding='utf8').write(h)
print('ok', total)
