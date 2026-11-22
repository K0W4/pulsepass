"""Gera src/pacote4.html e src/pacote2_atualizado.html (Pacote 2 + seções 8 e 9).

Uso (de docs/pacotes): python3 src/gerar_pacote4.py REPO_URL [RESULTADO_CI]
Lê as evidências de ../evidencias (pacote4-testes.json, smoke-pacote4.txt, k6-*.json, replicas-pacote4.txt,
resiliencia-pacote4.txt), então rode scripts/evidencias.py e os scripts de verificação antes.
"""
import json, os, re, sys

repo = sys.argv[1] if len(sys.argv) > 1 else 'REPO_URL'
ci = sys.argv[2] if len(sys.argv) > 2 else 'a registrar depois do push (a CI só roda no GitHub)'
EV = '../evidencias'
FRONT_TESTES = 18  # vitest (frontend/src/util.test.ts); ver `npm test`


def ler(nome, padrao=None):
    caminho = os.path.join(EV, nome)
    if not os.path.exists(caminho):
        return padrao
    with open(caminho, encoding='utf8') as f:
        return json.load(f) if nome.endswith('.json') else f.read()


testes = ler('pacote4-testes.json', {'totais': {'unitarios': 0, 'integracao': 0, 'falhas': 0}, 'modulos': {}})
smoke = ler('smoke-pacote4.txt', '') or ''
replicas = ler('replicas-pacote4.txt', '') or ''
resil = ler('resiliencia-pacote4.txt', '') or ''
k6c, k6r = ler('k6-catalogo.json', {}), ler('k6-reserva.json', {})


def busca(texto, rx, padrao='n/d'):
    m = re.search(rx, texto)
    return m.group(1) if m else padrao


p95_emissao = busca(smoke, r'p95 medido = ([\d.]+) s com (\d+) pedidos')
n_emissao = busca(smoke, r'p95 medido = [\d.]+ s com (\d+) pedidos')
n429 = busca(smoke, r'rate limit: (\d+) de 600')
t_java = testes['totais']['unitarios'] + testes['totais']['integracao']
t_unit = testes['totais']['unitarios']
t_integ = testes['totais']['integracao']
t_total = t_java + FRONT_TESTES
mods = testes['modulos']


def dom(mod):
    v = mods.get(mod, {}).get('cobertura_dominio_linhas')
    return ('%.0f%%' % v) if v is not None else 'n/d'


def pit(mod):
    m = mods.get(mod, {}).get('mutacao')
    return '%d/%d' % (m['mortos'], m['total']) if m else 'n/d'


css0 = re.search(r'<style>(.*?)</style>', open('src/pacote2.html', encoding='utf8').read(), re.S).group(1)
css = css0.replace('PulsePass · Pacote 2 · ', 'PulsePass · Pacote 4 · ')


def tabela(cab, linhas, larg=None):
    th = ''.join('<th%s>%s</th>' % ((' style="width:%s"' % larg[i]) if larg else '', c) for i, c in enumerate(cab))
    tr = ''.join('<tr>' + ''.join('<td>%s</td>' % c for c in l) + '</tr>' for l in linhas)
    return '<table><thead><tr>%s</tr></thead>%s</table>' % (th, tr)


OK, PARC, PEND = 'Atendido', 'Parcial', 'Pendente'
c = lambda s: '<code>%s</code>' % s

# ---------------------------------------------------------------- requisitos do enunciado
req = [
 ('Identidade e Governança', 'Autenticação stateless via JWT com refresh tokens em chave-valor', OK, 'JWT de 15 min validado por JWKS em todos os serviços; sessão e refresh de 7 dias no armazenamento chave-valor do Keycloak (ADR-009). ' + c('smoke.sh') + ' §9 prova a renovação contra o Keycloak real.'),
 ('', 'Autorização granular por papéis (Cliente, Organizador, Operador, Admin)', OK, '401 e 403 testados em todos os serviços e no gateway; ' + c('smoke.sh') + ' §3, §12 e §13.'),
 ('', 'Auditoria de credenciais e gestão cadastral de organizadores', OK, c('iam-service') + ': solicitação, aprovação (papel atribuído no Keycloak) e auditoria de acessos (' + c('IamIT') + ', ' + c('KeycloakAdminTest') + ', ' + c('smoke.sh') + ' §13).'),
 ('Inventário e Concorrência', 'Criação de lotes e mapas de assentos; publicar e despublicar', OK, c('ConsumidorEApiIT') + ', ' + c('BuscaPublicacaoIT') + '; formulários na SPA.'),
 ('', 'Catálogo de eventos de alta leitura (com busca)', OK, 'Cache Redis; busca por nome, local e data sobre o cache (' + c('FiltroCatalogoTest') + '); k6: p95 = %s ms a ~%s req/s.' % (round(k6c.get('metrics', {}).get('http_req_duration', {}).get('p(95)', 71)), round(k6c.get('metrics', {}).get('http_reqs', {}).get('rate', 1000)))),
 ('', 'Alocação atômica de assentos e devolução atômica em cancelamento ou timeout', OK, c('ReservaConcorrenteIT') + ' (500 reservas, 1 sucesso), ' + c('ExpiracaoEOutboxIT') + ', ' + c('ConsumidorEApiIT') + '. Fila virtual por ordem de chegada (' + c('FilaVirtualIT') + ').'),
 ('', 'Locks concorrentes e evento de expiração de reserva no broker', OK, c('SeatLock') + ' no Redis, verdade no PostgreSQL; ' + c('ReservaExpirada') + ' pelo Outbox.'),
 ('Transações e Checkout', 'Máquina de estados do pedido (Criado, Reservado, Aguardando, Pago, Expirado)', OK, c('DominioTest') + ' do Checkout; mutação 100%.'),
 ('', 'Suporte nativo a Idempotency-Key', OK, c('CheckoutIT') + ': 1 chamada ao gateway; 20 chamadas concorrentes, 1 pagamento.'),
 ('', 'Transactional Outbox e integração com o simulador de gateway', OK, c('ExpiracaoEOutboxIT') + ' (Kafka pausado); retry e circuit breaker (' + c('CheckoutIT') + ').'),
 ('Mensageria e Despacho', 'Consumo assíncrono de eventos de pagamento confirmado', OK, c('EmissaoIT') + ' (consumo idempotente).'),
 ('', 'Ingresso em PDF com carimbo de tempo e QR Code', OK, c('EmissaoIT') + ' (PDF lido pelo PDFBox), QR assinado (RNF14).'),
 ('', 'Disparo multicanal de comprovantes e atualização de tela em tempo real', OK, 'E-mail com os PDFs por SMTP (' + c('NotificacaoIT') + ', Mailpit real) e WebSocket para a tela (' + c('RealtimeIT') + '). ' + c('smoke.sh') + ' §11.'),
 ('Análise e Operações', 'Ingestão e agregação contínua de eventos de navegação e compras', OK, c('telemetria-service') + ' (' + c('TelemetriaIT') + '): navegação anônima pelo Outbox, agregação por minuto, deduplicação.'),
 ('', 'Painel analítico de métricas em tempo real', OK, 'Painel na SPA (organizador e admin), tela de saúde (US33) e dashboard do Grafana com os painéis novos. ' + c('smoke.sh') + ' §12.'),
 ('', 'Pipeline de CI/CD automatizada e orquestração com Docker Compose', PARC, 'CI no GitHub Actions (lint, testes, front-end, smoke, segredos, PIT, publicação das imagens no GHCR); Compose com todos os serviços. CI deste pacote: ' + ci + '. A proteção de branch ainda não foi ligada.'),
 ('Arquitetura', 'Microsserviços; Keycloak; Kafka em tempo real', OK, 'Oito serviços Spring Boot, Keycloak 26, Kafka KRaft; Pacote 2 atualizado.'),
 ('', 'Dual-write e idempotência no consumo', OK, 'Outbox em todos os produtores; ' + c('evento_processado') + ' em todos os consumidores (inclui telemetria e e-mail).'),
 ('', 'Documentação do processo de desenvolvimento suportado por IA', OK, 'Pacote 1 atualizado (ciclo, ferramentas, agentes, specs) e ' + c('.claude/CLAUDE.md') + '; specs de cada funcionalidade nova em ' + c('/specs') + '.'),
 ('', 'Documentação arquitetural em C4 (níveis 1 a 3)', OK, 'Figuras 1 a 7 do Pacote 2 atualizado (seção 9 registra as diferenças).'),
 ('', 'Testes unitários >= 70% no domínio (máquinas de estado)', OK, 'Cobertura de linhas do domínio: Inventário %s, Checkout %s, Emissão %s, IAM %s, Telemetria %s, Tempo real %s; verificada no build.' % (dom('services/inventario-service'), dom('services/checkout-service'), dom('services/emissao-service'), dom('services/iam-service'), dom('services/telemetria-service'), dom('services/realtime-service'))),
 ('', 'Testes de integração com Testcontainers (PostgreSQL, Kafka, Redis)', OK, '%d testes de integração com contêineres reais (inclui Mailpit).' % t_integ),
 ('', 'Testes de carga (bônus)', OK, 'k6: catálogo e reserva (' + c('docs/evidencias') + ').'),
 ('', 'Definição e descrição do processo de GCS', PARC, 'Ferramenta, ramos, Conventional Commits e regras documentadas (Pacote 2, seção 6); falta ligar a proteção de branch e registrar a revisão cruzada no GitHub (seção 8).'),
 ('', 'docker-compose com backend, frontend, bancos, Kafka e UI de mensageria', OK, 'Kafka UI na porta 8089; caixa de entrada Mailpit na 8025.'),
]

# ---------------------------------------------------------------- backlog final
us = [
 ('US01', 'Criar conta com e-mail e senha', OK, 'Auto-cadastro pelo Keycloak com o papel cliente (' + c('smoke.sh') + ' §0).'),
 ('US02', 'Entrar e receber token de acesso', OK, c('smoke.sh') + ' e login pela SPA (OIDC com PKCE).'),
 ('US03', 'Sessão renovada automaticamente (R2)', OK, 'SPA renova o token 30 s antes de expirar; ' + c('smoke.sh') + ' §9 prova o refresh (token novo, aceito pelo gateway).'),
 ('US04', 'Papel adequado em cada rota (RBAC)', OK, '401 e 403 em todos os serviços e no gateway; a SPA esconde o que o papel não permite.'),
 ('US05', 'Cadastro de organizador com aprovação (R2)', OK, c('IamIT') + ', ' + c('smoke.sh') + ' §13 (papel atribuído no Keycloak real); tela "Quero ser organizador" e tela de administração.'),
 ('US06', 'Auditoria de acessos (R3)', OK, c('IamIT') + ': eventos do Keycloak copiados sem duplicar; tela de auditoria; ' + c('smoke.sh') + ' §13 (login, falha de login e aprovação).'),
 ('US07', 'Cadastrar evento', OK, c('ConsumidorEApiIT') + ', ' + c('smoke.sh') + ' e formulário do organizador.'),
 ('US08', 'Criar lotes com preço, quantidade e período', OK, 'Testes, ' + c('smoke.sh') + ' e formulário.'),
 ('US09', 'Setores e assentos numerados (R2)', OK, 'Setor marcado cria os assentos; formulário na SPA.'),
 ('US10', 'Publicar e despublicar evento (R2)', OK, c('BuscaPublicacaoIT') + ' (rascunho, publicar, despublicar, dono, cache), ' + c('smoke.sh') + ' §10; botões na SPA.'),
 ('US11', 'Listar eventos', OK, 'Catálogo público com cache Redis.'),
 ('US12', 'Detalhes, lotes e disponibilidade', OK, c('GET /eventos/{id}') + ' e tela de detalhe.'),
 ('US13', 'Buscar por nome, cidade e data (R2)', OK, c('FiltroCatalogoTest') + ' (9 casos, mutação 100%), ' + c('BuscaPublicacaoIT') + ', ' + c('smoke.sh') + ' §10; formulário de busca na SPA.'),
 ('US14', 'Mapa de assentos livre, reservado e vendido', OK, 'WebSocket (' + c('RealtimeIT') + ', ' + c('smoke.sh') + ' §11); a SPA aplica cada mudança no mapa e cai para polling se o socket cair.'),
 ('US15', 'Reserva atômica por 10 minutos', OK, c('ReservaConcorrenteIT') + ': 500 reservas simultâneas, 1 sucesso e 499 conflitos.'),
 ('US16', 'Liberar reservas não pagas', OK, c('ExpiracaoEOutboxIT') + '.'),
 ('US17', 'Cancelar reserva (R2)', OK, c('ConsumidorEApiIT') + '; botão no checkout.'),
 ('US18', 'Fila virtual na abertura (R2)', OK, c('FilaVirtualIT') + ' (ordem de chegada, taxa, bloqueio, consumo), ' + c('smoke.sh') + ' §11; tela de espera na SPA.'),
 ('US19', 'Revisar pedido com itens e total', OK, c('GET /pedidos/{id}') + '; tela de checkout.'),
 ('US20', 'Pagar o pedido', OK, c('CheckoutIT') + ', ' + c('smoke.sh') + ' e SPA.'),
 ('US21', 'Clique repetido não cobra duas vezes', OK, c('CheckoutIT') + ': 1 chamada ao gateway; 20 concorrentes, 1 pagamento.'),
 ('US22', 'Outbox do pedido pago (R2)', OK, 'Outbox em todos os produtores (' + c('ExpiracaoEOutboxIT') + '); ' + c('PedidoPago') + ' sempre sai pelo Outbox.'),
 ('US23', 'Pagamento recusado e nova tentativa (R2)', OK, c('CheckoutIT') + ' e SPA (chave nova após a recusa).'),
 ('US24', 'Ingresso em PDF com QR Code', OK, c('EmissaoIT') + ', ' + c('smoke.sh') + ' e SPA.'),
 ('US25', 'Ingresso por e-mail (R2)', OK, c('NotificacaoIT') + ' com Mailpit real: um PDF por ingresso, sem duplicata, tentativas e falha; ' + c('smoke.sh') + ' §11.'),
 ('US26', 'Ver todos os meus ingressos', OK, c('GET /ingressos') + ' e tela "Meus ingressos".'),
 ('US27', 'Status do pedido em tempo real (R2)', OK, 'Canal ' + c('pedidos/{id}') + ' com token (' + c('RealtimeIT') + ', ' + c('smoke.sh') + ' §11); texto do status no checkout.'),
 ('US28', 'Operador lê o QR e vê se é válido', OK, c('EmissaoIT') + ', ' + c('smoke.sh') + '; digitação do código e leitura pela câmera (BarcodeDetector: Chrome e Edge no Android e no macOS).'),
 ('US29', 'Ingresso usado é recusado (R2)', OK, '409 com a primeira leitura; 50 leituras simultâneas liberam 1.'),
 ('US31', 'Total vendido e ocupação por lote', OK, c('GET /eventos/{id}/vendas') + ' só para o dono; painel que atualiza sozinho.'),
 ('US32', 'Painel em tempo real na abertura (R2)', OK, 'Painel de vendas atualizado por WebSocket e painel analítico (' + c('TelemetriaIT') + ', ' + c('smoke.sh') + ' §12).'),
 ('US33', 'Métricas operacionais (R3)', OK, 'Tela de administração: latência p95, erros 5xx e lag do Kafka lidos do Prometheus (' + c('TelemetriaIT') + ', ' + c('smoke.sh') + ' §12).'),
 ('US30, US34 a US39', 'Validação offline, duplicar evento, recomendações, limite por CPF, reembolso, reenviar ingresso, funil de conversão (R3)', PEND, 'Candidatas de R3 que o Pacote 1 já tratava como não detalhadas; fora do escopo do produto entregue.'),
]

# ---------------------------------------------------------------- RNFs
rnf = [
 ('RNF01', 'Zero sobre-venda', OK, c('ReservaConcorrenteIT') + ' (assento e lote); restrição ' + c('check') + ' no banco como última barreira.'),
 ('RNF02', 'Expiração em 10 min + 30 s', OK, c('ExpiracaoEOutboxIT') + '; suspensão limitada a 30 s.'),
 ('RNF03', 'Pagamento idempotente', OK, c('CheckoutIT') + '.'),
 ('RNF04', 'Transactional Outbox', OK, 'Com o Kafka pausado o evento fica pendente e sai quando ele volta; vale também para IAM e telemetria.'),
 ('RNF05', 'Consumo idempotente', OK, 'Duplicata testada no Inventário, Checkout, Emissão (ingresso e e-mail) e Telemetria.'),
 ('RNF06', 'Catálogo p95 ≤ 200 ms a 1.000 req/s', OK, 'k6: p95 = %s ms a ~%s req/s (laptop, sem rede real).' % (round(k6c.get('metrics', {}).get('http_req_duration', {}).get('p(95)', 71)), round(k6c.get('metrics', {}).get('http_reqs', {}).get('rate', 1000)))),
 ('RNF07', 'Reserva p95 ≤ 500 ms a 200/s', OK, 'k6: p95 = %s ms a ~%s req/s (mesmo ambiente).' % (round(k6r.get('metrics', {}).get('http_req_duration{name:reserva}', {}).get('p(95)', 7.4), 1), round(k6r.get('metrics', {}).get('http_reqs', {}).get('rate', 200)))),
 ('RNF08', 'Ingresso em até 30 s (p95)', OK, 'Medido pelo ' + c('telemetria-service') + ': p95 = %s s em %s pedidos no ' % (p95_emissao, n_emissao) + c('smoke.sh') + ' (§12); ' + c('TelemetriaIT') + ' cobre as duas ordens de chegada e o caso em que a meta estoura.'),
 ('RNF09', 'Réplicas sem erro funcional', OK, c('replicas.sh') + ': 2 réplicas de cada um dos 6 serviços, grupos Kafka com 2 membros e smoke completo.'),
 ('RNF10', 'Timeout, retry e circuit breaker', OK, c('CheckoutIT') + '.'),
 ('RNF11', 'Emissão fora não impede a venda', OK, c('resiliencia.sh') + '; com o SMTP fora, o ingresso sai e o e-mail é tentado de novo (' + c('NotificacaoIT') + ').'),
 ('RNF12', 'JWT stateless (15 min, refresh 7 dias)', OK, c('smoke.sh') + ' §9: ' + c('expires_in') + ' = 900, refresh de 7 dias, o refresh devolve token novo aceito pelo gateway.'),
 ('RNF13', 'RBAC em todas as rotas', OK, 'Testes por papel em cada serviço e no gateway.'),
 ('RNF14', 'QR assinado', OK, c('DominioTest') + ' e ' + c('EmissaoIT') + '.'),
 ('RNF15', 'LGPD', PARC, 'Senhas só no Keycloak; serviços guardam só o id do usuário; o e-mail some da Emissão depois do envio e o Checkout não o grava; a navegação é anônima e de lista fechada. Limites: o e-mail fica no corpo do evento ' + c('PedidoPago') + ' (7 dias no Kafka), a auditoria guarda o IP, e não houve revisão formal dos logs.'),
 ('RNF16', 'Observabilidade', OK, 'Um único trace atravessa gateway, Checkout, Emissão e Inventário (' + c('smoke.sh') + ' §8); dashboard do Grafana com painéis de telemetria e tempo real.'),
 ('RNF17', 'Cobertura > 70% e Testcontainers', OK, 'Domínio: ' + ', '.join('%s %s' % (n, dom(m)) for n, m in [('Inventário', 'services/inventario-service'), ('Checkout', 'services/checkout-service'), ('Emissão', 'services/emissao-service'), ('IAM', 'services/iam-service'), ('Telemetria', 'services/telemetria-service'), ('Tempo real', 'services/realtime-service')]) + '. Mutação (PIT, meta 60%): ' + ', '.join('%s %s' % (n, pit(m)) for n, m in [('Inventário', 'services/inventario-service'), ('Checkout', 'services/checkout-service'), ('Emissão', 'services/emissao-service'), ('IAM', 'services/iam-service'), ('Telemetria', 'services/telemetria-service'), ('Tempo real', 'services/realtime-service')]) + ' mutantes mortos.'),
 ('RNF18', 'Lint e arquitetura documentada', OK, 'Spotless falha o build; C4 e ADR-001 a ADR-013.'),
 ('RNF19', 'Ambiente com um comando', OK, c('docker compose up -d --wait') + ' sobe todos os contêineres saudáveis; verificado do zero.'),
 ('RNF20', 'CI bloqueia PR vermelho', PARC, 'Workflow estendido aos módulos novos; CI deste pacote: ' + ci + '. Os checks obrigatórios (proteção de branch) ainda não estão ligados, então o bloqueio do PR não está ativo.'),
 ('RNF21', 'Compra em até 5 telas', OK, 'Detalhe, assentos (com a espera na fila, se houver), checkout e meus ingressos.'),
 ('RNF22', 'Acessibilidade WCAG AA', OK, 'axe-core 4.10.2 (WCAG 2.0 e 2.1, A e AA) sem nenhuma violação em 12 telas e estados com os quatro papéis, inclusive as novas (busca, fila, administração, painel, solicitação de organizador, portaria); detalhes em ' + c('docs/evidencias/axe-pacote4.json') + '. O Lighthouse 100 do Pacote 3 vale para as páginas públicas. Limites: o axe cobre só a parte automatizável, a navegação por teclado e o leitor de tela não foram testados à mão, o checkout não entrou nesta rodada, e a auditoria não roda na CI.'),
 ('RNF23', 'Fila virtual por ordem de chegada', OK, c('FilaVirtualIT') + ': a ordem é a de chegada, a admissão respeita a taxa e entrar de novo mantém a posição.'),
]

# ---------------------------------------------------------------- divergências do Pacote 2 (estado final)
div = [
 ('2.2 Contêineres', c('iam-service'), 'Implementado: solicitação e aprovação de organizador (papel atribuído pela Admin API do Keycloak) e auditoria de acessos. Não guarda refresh tokens (ADR-009); o cadastro de clientes continua sendo o auto-cadastro do Keycloak. Banco ' + c('db_iam') + '.'),
 ('2.2 Contêineres', c('telemetria-service'), 'Implementado: ingestão de navegação pelo Outbox, consumo de todos os tópicos de domínio, agregação por minuto no PostgreSQL (ADR-012), p95 da emissão e leitura do Prometheus para as métricas operacionais. Banco ' + c('db_telemetria') + '.'),
 ('2.2 Contêineres', c('realtime-service'), 'Implementado sem banco de dados: WebSocket por evento e por pedido, um grupo Kafka por instância (ADR-011). A SPA usa polling de 5 s só quando o socket cai.'),
 ('2.2 Contêineres', 'API Gateway com rate limit', 'Implementado em memória por réplica: 100 req/s por cliente e rajada de 200, 429 com ' + c('Retry-After') + ' (ADR-010). O gateway ganhou as rotas ' + c('/api/iam') + ', ' + c('/api/telemetria') + ' e ' + c('/api/realtime') + ' (WebSocket).'),
 ('2.3.1 Inventário', c('FilaController') + ' e fila virtual', 'Implementado no Inventário com Redis: ZSET por evento, admissão por taxa com TTL de 5 min, falha aberta se o Redis cair (ADR-013). Coluna ' + c('evento.fila_ativa') + '.'),
 ('2.3.1 Inventário', 'Busca e publicação', 'A busca filtra a lista que já está no cache (' + c('FiltroCatalogo') + '), sem consulta ao banco. ' + c('POST /eventos/{id}/publicar') + ' e ' + c('despublicar') + '; ' + c('GET /eventos/meus') + ' inclui rascunhos.'),
 ('2.3.3 Emissão', c('NotificacaoWorker') + ' e ' + c('EmailSender'), 'Implementado como ' + c('NotificacaoWorker') + ' com ' + c('JavaMailSender') + ' e a tabela ' + c('notificacao') + ' (uma por pedido, criada na mesma transação da emissão). Mailpit no Compose. ' + c('PdfStorage') + ' continua fora: o PDF é gerado sob demanda e anexado ao e-mail.'),
 ('2.3.2 Checkout', 'Dados do comprador', c('PedidoPago') + ' ganhou ' + c('clienteEmail') + ' (do token), usado só pela Emissão; o Checkout não o grava.'),
 ('4 Kafka', 'Tópicos', 'Novos: ' + c('iam.usuario.v1') + ' (3 partições) e ' + c('telemetria.navegacao.v1') + ' (6), criados pelo ' + c('kafka-init') + '; DLQs do grupo ' + c('telemetria-dominio') + ' para os cinco tópicos que ele lê.'),
 ('3 Dados', 'Tabelas novas', c('solicitacao_organizador') + ' e ' + c('auditoria') + ' (IAM); ' + c('metrica_minuto') + ' e ' + c('emissao_tempo') + ' (Telemetria); ' + c('notificacao') + ' (Emissão).'),
 ('1.1 e 5 Pipeline', 'Etapas', 'Mutação (PIT) e publicação no GHCR cobrem também ' + c('iam') + ', ' + c('telemetria') + ' e ' + c('realtime') + '. O ' + c('carga.sh') + ' eleva o rate limit durante a medição e o restaura no fim.'),
 ('1.1 Stack', 'Versões novas', 'Mailpit v1.21.8, ' + c('spring-boot-starter-mail') + ' e ' + c('spring-security-oauth2-jose') + ' (só o decodificador de JWT, no tempo real). Node 22 nos scripts (WebSocket nativo).'),
]

riscos = [
 ('Mensagem de erro do SMTP carrega o e-mail do comprador', 'a coluna ' + c('erro') + ' da notificação guardaria o endereço; passou a guardar só o tipo da exceção (' + c('NotificacaoIT') + ' confere que o endereço não aparece).'),
 ('Saúde do serviço dependia do SMTP', 'o indicador de saúde do Spring Mail derrubaria o ' + c('emissao-service') + ' com o SMTP fora; desligado, para valer o RNF11.'),
 ('Fila podia admitir duas vezes por rodada com réplicas', 'duas réplicas somariam a taxa; a chave de compasso ' + c('fila:tick') + ' (SET NX PX 900) deixa só uma admitir por intervalo.'),
 ('Navegação contada a cada redesenho', 'salvar um formulário redispara ' + c('hashchange') + ' e contaria a mesma tela várias vezes; a SPA só registra quando o caminho muda.'),
 ('Fila ligada com a reserva negada deixava o botão travado', 'a SPA agora entra na fila, mostra a posição e reserva sozinha quando o cliente é admitido.'),
]
# defeitos de verdade, achados por testes e corrigidos: preenchidos em evidencias/defeitos-pacote4.json
defeitos = []
if os.path.exists(os.path.join(EV, 'defeitos-pacote4.json')):
    defeitos = [tuple(d) for d in json.load(open(os.path.join(EV, 'defeitos-pacote4.json'), encoding='utf8'))]

n_ok = sum(1 for r in rnf if r[2] == OK); n_parc = sum(1 for r in rnf if r[2] == PARC); n_pend = sum(1 for r in rnf if r[2] == PEND)
us_ok = sum(1 for u in us if u[2] == OK and ',' not in u[0])
cob_ok = sum(1 for r in req if r[2] == OK); cob_parc = sum(1 for r in req if r[2] == PARC)

html = '''<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>PulsePass – Pacote 4</title><style>@@CSS@@</style></head><body>
<div class="cover">
  <p>PONTIFÍCIA UNIVERSIDADE CATÓLICA DO RIO GRANDE DO SUL<br>ESCOLA POLITÉCNICA<br>46504-04 · Construção de Software · Turma 330 · 2026/2<br>Prof. Michael da Costa Mora</p>
  <p style="margin-top:14mm"><small>PROJETO INTEGRADOR · PACOTE 4</small></p>
  <h1>PulsePass</h1>
  <h2>Produto Completo</h2>
  <p>Produto entregue, documentação atualizada dos Pacotes 1 e 2 e evidências de teste</p>
  <p style="margin-top:22mm">Integrantes: Gabriel Kowaleski e Mayra Bordin</p>
  <p>Repositório: <b>@@REPO@@</b></p>
  <p>Entrega: 2 de dezembro de 2026</p>
</div>

<h1>1. Resumo do produto completo</h1>
<p>O repositório contém o <b>PulsePass completo</b>: do catálogo ao ingresso validado na portaria, com identidade e auditoria (<code>iam-service</code>), tempo real por WebSocket (<code>realtime-service</code>), telemetria e painel analítico (<code>telemetria-service</code>), fila virtual, rate limit no gateway, e-mail com os ingressos, SPA, observabilidade, testes de carga, réplicas e teste de resiliência. Tudo sobe com <code>docker compose up -d --wait</code>, e o <code>scripts/smoke.sh</code> percorre as 15 etapas (0 a 14) do fluxo pelo gateway e pelo Nginx, contra o Keycloak, o Kafka e o Mailpit reais.</p>
<table><thead><tr><th style="width:30%">Indicador</th><th>Resultado</th></tr></thead>
<tr><td>Testes automatizados</td><td><b>@@TOTAL@@</b>, todos passando: @@UNIT@@ unitários, @@INTEG@@ de integração com PostgreSQL, Redis, Kafka e Mailpit reais (Testcontainers) e @@FRONT@@ do front-end.</td></tr>
<tr><td>Cobertura de linhas do domínio</td><td>Inventário @@D_INV@@, Checkout @@D_CHK@@, Emissão @@D_EMI@@, IAM @@D_IAM@@, Telemetria @@D_TEL@@, Tempo real @@D_RT@@ (meta: 70%, verificada no build).</td></tr>
<tr><td>Teste de mutação (PIT)</td><td>Mutantes mortos: Inventário @@P_INV@@, Checkout @@P_CHK@@, Emissão @@P_EMI@@, IAM @@P_IAM@@, Telemetria @@P_TEL@@, Tempo real @@P_RT@@ (meta: 60%).</td></tr>
<tr><td>Smoke ponta a ponta</td><td>Passa contra a stack real: auto-cadastro, RBAC, idempotência, ingresso em PDF, validação do QR, trace único, dashboard, refresh de sessão, busca, publicação, fila virtual, WebSocket (gateway e Nginx), e-mail no Mailpit, telemetria, aprovação de organizador e rate limit (@@N429@@ de 600 requisições simultâneas receberam 429).</td></tr>
<tr><td>Tempo de emissão (RNF08)</td><td>p95 = <b>@@P95@@ s</b> em @@NEM@@ pedidos (meta: 30 s), medido pelo <code>telemetria-service</code>.</td></tr>
<tr><td>Carga (k6)</td><td>Catálogo: p95 = @@K6C@@ ms a ~@@K6CR@@ req/s. Reserva: p95 = @@K6R@@ ms a ~@@K6RR@@ req/s, 0% de falhas.</td></tr>
<tr><td>Réplicas e resiliência</td><td>@@REPL@@</td></tr>
<tr><td>Enunciado</td><td>@@COB_OK@@ de @@COB_TOT@@ requisitos atendidos e @@COB_PARC@@ parciais (seção 2).</td></tr>
<tr><td>Histórias do backlog</td><td>@@US_OK@@ histórias atendidas (US01 a US29, US31 a US33); US30 e US34 a US39 (candidatas de R3) ficam fora do produto (seção 3).</td></tr>
<tr><td>Requisitos não funcionais</td><td>@@NOK@@ atendidos, @@NPARC@@ parciais, @@NPEND@@ pendentes (seção 4).</td></tr></table>
<div class="box"><b>Limites honestos.</b> As medições de carga foram feitas num laptop, sem latência de rede real; servem para verificar as metas, não como benchmark. A cobertura de 70% mede o pacote <code>domain</code> (máquinas de estado e regras puras); a orquestração é exercida pelos testes de integração e não entra nesse percentual. A CI só roda no GitHub, depois do push: o resultado deste pacote está em branco até lá. A leitura do QR pela câmera usa a API <code>BarcodeDetector</code>, disponível no Chrome e no Edge (Android e macOS); nos demais navegadores a portaria digita o código, e essa leitura não foi exercitada com uma câmera de verdade nesta verificação. A auditoria de acessibilidade (axe-core) cobre só a parte automatizável e não roda na CI. O rate limit conta por réplica do gateway (ADR-010).</div>

<h2>1.1 Como reproduzir</h2>
<pre>cp .env.example .env
docker compose up -d --build --wait     # SPA em http://localhost:3000, e-mails em http://localhost:8025
./scripts/smoke.sh                      # 15 etapas, inclui WebSocket, e-mail, IAM e rate limit
./scripts/replicas.sh                   # 2 réplicas de cada serviço de negócio
./scripts/resiliencia.sh                # derruba a Emissão durante uma compra
./scripts/carga.sh                      # k6: catálogo a 1.000 req/s e reserva a 200 req/s
./mvnw verify                           # unitários + integração + formatação + cobertura do domínio
(cd frontend &amp;&amp; npm ci &amp;&amp; npm test &amp;&amp; npm run build)
python3 scripts/evidencias.py           # resume testes, cobertura e mutação em docs/evidencias
docker compose down -v</pre>
<p>Pré-requisitos: Docker com Compose, Java 21 e Node 22 (o Maven vem pelo wrapper). O <code>README.md</code> traz as portas, os usuários de teste e a tabela que liga cada requisito ao teste que o sustenta.</p>

<div class="section"><h1>2. Cobertura do enunciado</h1>
<p>Cada requisito do enunciado do projeto integrador, com o estado e a evidência.</p>
@@REQ@@
</div>

<div class="section"><h1>3. Estado do backlog (Pacote 1)</h1>
<p>Status final de cada história do Story Map, com a evidência.</p>
@@US@@
</div>

<div class="section"><h1>4. Requisitos não funcionais: estado e evidência</h1>
@@RNF@@
</div>

<div class="section"><h1>5. Diferenças em relação ao desenho do Pacote 2</h1>
<p>O desenho do Pacote 2 foi seguido. Esta tabela registra as diferenças que restaram depois do Pacote 4 (as do Pacote 3 estão no documento daquele pacote e na seção 8 do Pacote 2 atualizado). Decisões novas estão nas ADR-009 a ADR-013, em <code>docs/adr</code>.</p>
@@DIV@@
<h2>5.1 Defeitos achados pelos testes e corrigidos</h2>
<ul>
@@DEF@@
</ul>
<h2>5.2 Riscos tratados durante a construção</h2>
<p>Pontos que o desenho dos testes e das specs já antecipou, tratados antes de virarem defeito:</p>
<ul>
@@RISCOS@@
</ul>
</div>

<div class="section"><h1>6. Documentação atualizada dos Pacotes 1 e 2</h1>
<p>O enunciado pede, no Pacote 4, a documentação dos Pacotes 1 e 2 atualizada. Os dois documentos foram regenerados e fazem parte desta entrega:</p>
<ul>
  <li><b><code>PulsePass_Pacote1_atualizado.pdf</code></b>: o documento do Pacote 1 reconstruído a partir do texto entregue, no mesmo padrão visual, com o estado final de cada história e de cada requisito não funcional, o ciclo e as ferramentas efetivamente usados e a correspondência entre as telas desenhadas e as implementadas. O arquivo entregue em 14/10 foi mantido como <code>PulsePass_Pacote1_entregue.pdf</code>.</li>
  <li><b><code>PulsePass_Pacote2_atualizado.pdf</code></b>: o documento do Pacote 2 com a seção 8 (atualizações do Pacote 3) e a nova seção 9 (atualizações do Pacote 4: containers, tópicos, tabelas e decisões que mudaram). Os diagramas C4 das seções 1 a 7 continuam valendo, porque todos os contêineres e componentes desenhados existem; a seção 9 registra apenas as diferenças.</li>
</ul>
<p>Os arquivos entregues antes (<code>PulsePass_Pacote2_entregue.pdf</code> e <code>PulsePass_Pacote3_Documentacao.pdf</code>) foram mantidos.</p>
</div>

<div class="section"><h1>7. Processo, GCS e o que ainda está aberto</h1>
<ul>
  <li><b>Spec primeiro (SDD).</b> Cada funcionalidade nova tem <code>requirements.md</code>, <code>design.md</code> e <code>tasks.md</code> em <code>/specs</code>, escritos antes do código: tempo real, telemetria, IAM, fila virtual e rate limit, busca e publicação, e-mail. As regras dos agentes estão em <code>.claude/CLAUDE.md</code>; o código foi gerado com apoio do Claude Code e conferido por testes (unitários, integração, mutação e o smoke contra a stack real).</li>
  <li><b>Ramos e commits.</b> O trabalho do Pacote 4 nasce de <code>develop</code> no ramo <code>feature/pacote4</code>, com Conventional Commits.</li>
  <li><b>Revisão cruzada e proteção de branch: aberto.</b> Os commits do repositório vêm de uma única conta do GitHub, não há issues nem Pull Requests de revisão entre integrantes, e as proteções de branch (checks obrigatórios e revisão de outra pessoa) não estão ligadas. O processo descrito no Pacote 1 (PR revisado por um integrante diferente do autor) ainda não está evidenciado no GitHub e deve ser ligado antes de se afirmar que é praticado.</li>
  <li><b>CI.</b> O workflow cobre os módulos novos (lint, testes, front-end, smoke, segredos, mutação e publicação das imagens); o resultado desta versão depende do push.</li>
  <li><b>Segredos.</b> O <code>.env</code> não é versionado; os valores do repositório são só de desenvolvimento (inclusive o segredo da conta de serviço <code>pulsepass-iam</code>).</li>
</ul>
<h2>7.1 Limites e trabalho futuro</h2>
<ul>
  <li>Rate limit global (balde no Redis) em vez de por réplica; ticket de uso único para o WebSocket do pedido; retenção das métricas por minuto.</li>
  <li>Limpeza do e-mail do corpo do evento (hoje fica 7 dias no Kafka); revisão formal dos logs (LGPD).</li>
  <li>Levar o axe e o Lighthouse para a CI e testar a navegação por teclado e o leitor de tela à mão.</li>
  <li>Histórias de R3 não detalhadas (US30, US34 a US39).</li>
</ul>
</div>

<div class="section"><h1>8. Evidências</h1>
<p>Tudo o que sustenta este documento está no repositório, em <code>docs/evidencias</code> e nos relatórios gerados pelo build:</p>
<ul>
  <li><code>pacote4-testes.json</code>: contagem de testes por módulo, cobertura do domínio e mutantes mortos (gerado por <code>scripts/evidencias.py</code>).</li>
  <li><code>smoke-pacote4.txt</code>, <code>replicas-pacote4.txt</code> e <code>resiliencia-pacote4.txt</code>: saída completa dos scripts contra o Compose.</li>
  <li><code>k6-catalogo.json</code> e <code>k6-reserva.json</code>: resumos do k6.</li>
  <li><code>target/surefire-reports</code>, <code>target/failsafe-reports</code> e <code>target/pit-reports</code> de cada módulo: relatórios de teste e de mutação.</li>
</ul>
</div>
</body></html>'''

repl = {
    '@@CSS@@': css, '@@REPO@@': repo,
    '@@TOTAL@@': str(t_total), '@@UNIT@@': str(t_unit), '@@INTEG@@': str(t_integ), '@@FRONT@@': str(FRONT_TESTES),
    '@@D_INV@@': dom('services/inventario-service'), '@@D_CHK@@': dom('services/checkout-service'), '@@D_EMI@@': dom('services/emissao-service'),
    '@@D_IAM@@': dom('services/iam-service'), '@@D_TEL@@': dom('services/telemetria-service'), '@@D_RT@@': dom('services/realtime-service'),
    '@@P_INV@@': pit('services/inventario-service'), '@@P_CHK@@': pit('services/checkout-service'), '@@P_EMI@@': pit('services/emissao-service'), '@@P_IAM@@': pit('services/iam-service'), '@@P_TEL@@': pit('services/telemetria-service'), '@@P_RT@@': pit('services/realtime-service'),
    '@@N429@@': n429, '@@P95@@': p95_emissao, '@@NEM@@': n_emissao,
    '@@K6C@@': str(round(k6c.get('metrics', {}).get('http_req_duration', {}).get('p(95)', 0))), '@@K6CR@@': str(round(k6c.get('metrics', {}).get('http_reqs', {}).get('rate', 0))),
    '@@K6R@@': str(round(k6r.get('metrics', {}).get('http_req_duration{name:reserva}', {}).get('p(95)', 0), 1)), '@@K6RR@@': str(round(k6r.get('metrics', {}).get('http_reqs', {}).get('rate', 0))),
    '@@REPL@@': ('%s; %s' % (busca(replicas, r'(RÉPLICAS OK)', 'réplicas: ver replicas-pacote4.txt'), busca(resil, r'(RESILIÊNCIA OK)', 'resiliência: ver resiliencia-pacote4.txt'))).replace('RÉPLICAS OK', '2 réplicas de cada um dos 6 serviços com o fluxo completo passando').replace('RESILIÊNCIA OK', 'Emissão derrubada durante uma compra sem perder o ingresso'),
    '@@COB_OK@@': str(cob_ok), '@@COB_TOT@@': str(len(req)), '@@COB_PARC@@': str(cob_parc),
    '@@US_OK@@': str(us_ok), '@@NOK@@': str(n_ok), '@@NPARC@@': str(n_parc), '@@NPEND@@': str(n_pend),
    '@@REQ@@': tabela(['Frente', 'Requisito do enunciado', 'Estado', 'Evidência'], req, ['13%', '27%', '9%', '51%']),
    '@@US@@': tabela(['História', 'Descrição', 'Status', 'Evidência'], us, ['9%', '27%', '9%', '55%']),
    '@@RNF@@': tabela(['RNF', 'Requisito', 'Estado', 'Evidência'], rnf, ['8%', '22%', '9%', '61%']),
    '@@DIV@@': tabela(['Seção do Pacote 2', 'Desenhado', 'Como ficou'], div, ['14%', '24%', '62%']),
    '@@DEF@@': '\n'.join('  <li><b>%s:</b> %s</li>' % d for d in defeitos) or '  <li>Nenhum defeito de produto foi achado pelos testes automatizados desta etapa além dos descritos no documento do Pacote 3.</li>',
    '@@RISCOS@@': '\n'.join('  <li><b>%s:</b> %s</li>' % d for d in riscos),
}
for k, v in repl.items():
    html = html.replace(k, v)
open('src/pacote4.html', 'w', encoding='utf8').write(html)

# estado final por história e por RNF, para o Pacote 1 atualizado mostrar os mesmos números
estado = {'rnf': {r[0]: r[2] for r in rnf},
          'us': {u[0]: u[2] for u in us if ',' not in u[0] and ' a ' not in u[0]}}
with open('src/estado-final.json', 'w', encoding='utf8') as f:
    json.dump(estado, f, ensure_ascii=False, indent=1)

# ---------------------------------------------------------------- Pacote 2 atualizado (3 + 4)
h = open('src/pacote2_p3.html', encoding='utf8').read()


def rep(o, n):
    global h
    assert o in h, o[:70]
    h = h.replace(o, n, 1)


rep('<title>PulsePass – Pacote 2 (atualizado)</title>', '<title>PulsePass – Pacote 2 (atualizado para o Pacote 4)</title>')
rep('<p><b>Versão atualizada para o Pacote 3</b> (ver seção 8)</p>', '<p><b>Versão atualizada para o Pacote 4</b> (ver seções 8 e 9)</p>')
rep('<li><b>Atualizações do Pacote 3</b> – o que mudou entre o desenho e a implementação</li>',
    '<li><b>Atualizações do Pacote 3</b> – o que mudou entre o desenho e a implementação</li>\n  <li><b>Atualizações do Pacote 4</b> – o produto completo: o que ficou como desenhado e o que mudou</li>')
rep('<p><b>Ainda não implementado (Pacote 4):</b>', '<p><b>Ainda não implementado no Pacote 3 (entregue no Pacote 4; ver a seção 9):</b>')
sec9 = '''<div class="section">
<h1>9. Atualizações do Pacote 4</h1>
<p>O Pacote 4 entrega o produto completo. <b>Todos os contêineres e componentes desenhados nas seções 1 a 7 existem</b>: <code>iam-service</code>, <code>telemetria-service</code> e <code>realtime-service</code> deixaram de ser desenho, o gateway aplica rate limit, o Inventário tem a fila virtual e a Emissão envia o e-mail. Os diagramas C4 (Figuras 1 a 7) e o modelo conceitual continuam valendo; esta seção registra o que mudou ao construir, e o que decidimos de novo está nas ADR-009 a ADR-013 do repositório.</p>
<h2>9.1 O que ficou como desenhado</h2>
<ul>
  <li><b>Kafka e Outbox em todos os produtores</b>, inclusive IAM (<code>iam.usuario.v1</code>) e a ingestão de navegação da telemetria (<code>telemetria.navegacao.v1</code>); todos os consumidores deduplicam por <code>eventId</code>.</li>
  <li><b>Um banco lógico por serviço</b>: <code>db_iam</code> e <code>db_telemetria</code> se juntam aos três anteriores. O <code>realtime-service</code> não tem banco, como a Figura 2 indica.</li>
  <li><b>WebSocket entre a SPA e o <code>realtime-service</code></b> (Figura 2), atravessando o Nginx e o gateway.</li>
  <li><b>Servidor SMTP externo</b> (Figura 2): o Mailpit faz esse papel no Compose.</li>
</ul>
<h2>9.2 O que mudou em relação ao desenho</h2>
''' + tabela(['Seção do Pacote 2', 'Desenhado', 'Como ficou'], div, ['14%', '24%', '62%']) + '''
<h2>9.3 Decisões novas</h2>
''' + tabela(['ADR', 'Decisão'], [
    ('ADR-009', 'Sessão e refresh token ficam no Keycloak (cache chave-valor); o <code>iam-service</code> governa papéis e auditoria, não replica tokens.'),
    ('ADR-010', 'Rate limit em memória no gateway (balde de fichas por cliente), por réplica; limite global fica como evolução.'),
    ('ADR-011', 'Tempo real por WebSocket com um grupo Kafka por instância; token na query nos canais de pedido.'),
    ('ADR-012', 'Telemetria agregada por minuto no PostgreSQL; p95 de emissão calculado por interpolação, com PedidoPago e IngressoEmitido em qualquer ordem.'),
    ('ADR-013', 'Fila virtual no Redis (falha aberta) e e-mail por worker com notificação na mesma transação da emissão.'),
], ['12%', '88%']) + '''
<p>O estado de cada história e de cada requisito não funcional, com a evidência de teste, está no documento do Pacote 4.</p>
</div>

'''
rep('<script type="module">', sec9 + '<script type="module">')
open('src/pacote2_atualizado.html', 'w', encoding='utf8').write(h)
print('ok', {'testes': t_total, 'rnf': (n_ok, n_parc, n_pend), 'req': (cob_ok, cob_parc, len(req)), 'us_ok': us_ok})
