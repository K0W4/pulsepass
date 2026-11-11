// RNF07: reserva de assento com p95 <= 500 ms sob 200 reservas/s. Cada requisição reserva um assento
// diferente (carga realista, sem disputa pelo mesmo assento).
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const GW = __ENV.GW || 'http://api-gateway:8080';
const KC = __ENV.KC || 'http://keycloak:8080';
const TAXA = Number(__ENV.RATE || 200);
const DURACAO = Number(__ENV.SECONDS || 30);
const ASSENTOS = TAXA * DURACAO + 500;

export const options = {
  scenarios: {
    reserva: {
      executor: 'constant-arrival-rate',
      rate: TAXA,
      timeUnit: '1s',
      duration: `${DURACAO}s`,
      preAllocatedVUs: 100,
      maxVUs: 600,
    },
  },
  thresholds: {
    'http_req_duration{name:reserva}': ['p(95)<500'],
    'http_req_failed{name:reserva}': ['rate<0.01'],
  },
};

function token(usuario) {
  const r = http.post(`${KC}/realms/pulsepass/protocol/openid-connect/token`, {
    grant_type: 'password', client_id: 'pulsepass-api',
    username: `${usuario}@pulsepass.test`, password: __ENV.DEV_USER_PASSWORD,
  });
  return r.json('access_token');
}

const json = (t) => ({ headers: { Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' }, timeout: '120s' });

export function setup() {
  const org = token('organizador');
  const ev = http.post(`${GW}/api/eventos`, JSON.stringify({ nome: `Carga ${Date.now()}`, local: 'Arena', dataHora: '2027-06-01T20:00:00Z' }), json(org)).json('id');
  const lote = http.post(`${GW}/api/eventos/${ev}/lotes`, JSON.stringify({ nome: 'Pista', preco: 100, quantidade: 10, inicioVenda: '2026-01-01T00:00:00Z', fimVenda: '2027-05-01T00:00:00Z' }), json(org)).json('id');
  const codigos = Array.from({ length: ASSENTOS }, (_, i) => `C${i}`);
  const s = http.post(`${GW}/api/eventos/${ev}/setores`, JSON.stringify({ nome: 'Carga', marcado: true, loteId: lote, codigos }), json(org));
  if (s.status !== 201) throw new Error(`setor: ${s.status}`);
  const assentos = http.get(`${GW}/api/eventos/${ev}/assentos`, { timeout: '120s' }).json().map((a) => a.id);
  return { cliente: token('cliente'), evento: ev, assentos };
}

export default function (d) {
  const i = exec.scenario.iterationInTest;
  const r = http.post(`${GW}/api/reservas`, JSON.stringify({ eventoId: d.evento, assentoIds: [d.assentos[i]] }),
    { ...json(d.cliente), tags: { name: 'reserva' } });
  check(r, { 'reserva 201': (x) => x.status === 201 });
}
