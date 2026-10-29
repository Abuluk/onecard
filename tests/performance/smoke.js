import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 5,
  duration: '10s',
  thresholds: {
    http_req_failed: ['rate<0.01'], // 错误率低于 1%
    http_req_duration: ['p(95)<800'], // 95% 请求在 800ms 内
  },
};

// 直接读取传入的完整 URL
const TARGET_URL = __ENV.TARGET_URL;

export default function () {
  const res = http.get(TARGET_URL);
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
  sleep(1);
}