// RNF06: catálogo de eventos com p95 <= 200 ms sob 1.000 req/s (leitura via cache Redis).
import http from 'k6/http';
import { check } from 'k6';

const GW = __ENV.GW || 'http://api-gateway:8080';

export const options = {
  scenarios: {
    catalogo: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 1000),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 200,
      maxVUs: 1000,
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<200'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  // aquece o cache e confirma que há eventos
  const r = http.get(`${GW}/api/eventos`);
  if (r.status !== 200) throw new Error(`catálogo indisponível: ${r.status}`);
}

export default function () {
  const r = http.get(`${GW}/api/eventos`);
  check(r, { 'status 200': (x) => x.status === 200 });
}
