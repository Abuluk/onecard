import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 5,
  duration: '20s',
  thresholds: {
    // 错误率必须低于 1%，否则 k6 以非零退出码结束
    http_req_failed: ['rate<0.01'],
    // 95% 的请求必须在 800ms 内完成
    http_req_duration: ['p(95)<800'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const res = http.get(`${BASE_URL}/actuator/health`);
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
  sleep(1);
}