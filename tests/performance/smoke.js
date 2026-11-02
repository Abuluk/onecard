import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 1,
  duration: '10s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
    checks: ['rate>0.99'],
  },
};

const BASE_URL = __ENV.BASE_URL;

if (!BASE_URL) {
  throw new Error('BASE_URL is required');
}

export default function () {
  const res = http.get(`${BASE_URL}/api/demo/ping`);
  check(res, {
    'status is 200': (r) => r.status === 200,
    'result code is success': (r) => r.json('code') === 'SUCCESS',
  });
  sleep(1);
}
