import http from 'k6/http';
import { check, sleep } from 'k6';
import encoding from 'k6/encoding';

// ponytail: um script cobre 6 endpoints, sem abstração extra
export const options = {
  insecureSkipTLSVerify: true,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
  stages: [
    { duration: '30s', target: 10 },
    { duration: '1m', target: 10 },
    { duration: '30s', target: 0 },
  ],
};

const BASE_URL = __ENV.BASE_URL || 'https://jee.lab.dev/temperatura';
const USER = __ENV.APP_USERNAME || 'admin';
const PASS = __ENV.APP_PASSWORD || 'admin123';
const AUTH = `Basic ${encoding.b64encode(`${USER}:${PASS}`)}`;

const endpoints = [
  { path: '/converter/ctof', val: 100, expect: 212 },      // C→F
  { path: '/converter/ctok', val: 0, expect: 273.15 },     // C→K
  { path: '/converter/ftoc', val: 32, expect: 0 },         // F→C
  { path: '/converter/ftok', val: 32, expect: 273.15 },    // F→K
  { path: '/converter/ktoc', val: 273.15, expect: 0 },     // K→C
  { path: '/converter/ktof', val: 273.15, expect: 32 },    // K→F
];

export default function () {
  const headers = { Authorization: AUTH, Accept: 'application/json' };
  for (const e of endpoints) {
    const url = `${BASE_URL}${e.path}/${e.val}`;
    const res = http.get(url, { headers, tags: { endpoint: e.path } });
    check(res, {
      [`${e.path} 200`]: (r) => r.status === 200,
      [`${e.path} body number`]: (r) => !isNaN(parseFloat(r.body)),
    });
    sleep(0.2);
  }
  // variação: valor aleatório para aquecer histogramas/GC
  const rnd = Math.floor(Math.random() * 100);
  http.get(`${BASE_URL}/converter/ctof/${rnd}`, { headers });
  sleep(0.5);
}

export function handleSummary(data) {
  return {
    stdout: `VUs:${data.metrics.vus.values.value} reqs:${data.metrics.http_reqs.values.count} p95:${data.metrics.http_req_duration.values['p(95)']}ms fails:${data.metrics.http_req_failed.values.rate}\n`,
  };
}
