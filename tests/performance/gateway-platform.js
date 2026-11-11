import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { check } from 'k6';
import { sleep } from 'k6';

const gatewayUrl = __ENV.BASE_URL;
const accessToken = __ENV.AUTH_TOKEN;
const virtualUsers = Number(__ENV.VUS || 50);
const duration = __ENV.DURATION || '30m';
const durationParts = /^(\d+)(s|m|h)$/.exec(duration);
if (!durationParts) {
  throw new Error('DURATION must use seconds, minutes, or hours');
}

const durationSeconds = Number(durationParts[1]) * { s: 1, m: 60, h: 3600 }[durationParts[2]];
const minimumRequests = Math.ceil((3200 / 60) * durationSeconds);
const targetRatePerSecond = 3210 / 60;
const iterationIntervalSeconds = virtualUsers / targetRatePerSecond;
const successfulResponses = new Counter('gateway_http_200');
const otherResponses = new Counter('gateway_http_non_200');
let firstIteration = true;

if (!gatewayUrl || !accessToken) {
  throw new Error('BASE_URL and AUTH_TOKEN are required');
}

if (!Number.isInteger(virtualUsers) || virtualUsers < 1 || virtualUsers > 500) {
  throw new Error('VUS must be an integer between 1 and 500');
}

export const options = {
  scenarios: {
    gateway_stub: {
      executor: 'constant-vus',
      vus: virtualUsers,
      duration,
      gracefulStop: '30s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  thresholds: {
    checks: ['rate>0.99'],
    http_reqs: [`count>=${minimumRequests}`],
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

function randomHex(length) {
  const digits = '0123456789abcdef';
  let value = '';
  for (let index = 0; index < length; index += 1) {
    value += digits[Math.floor(Math.random() * digits.length)];
  }
  return value;
}

function responseHeader(headers, name) {
  const header = Object.entries(headers).find(([key]) => key.toLowerCase() === name);
  return header ? header[1] : '';
}

export default function () {
  if (firstIteration) {
    sleep(Math.random() * iterationIntervalSeconds);
    firstIteration = false;
  }

  const iterationStartedAt = Date.now();
  const traceId = randomHex(32);
  const spanId = randomHex(16);
  const response = http.get(`${gatewayUrl}/api/account/platform-probe`, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
      traceparent: `00-${traceId}-${spanId}-01`,
    },
  });
  if (response.status === 200) {
    successfulResponses.add(1);
  } else {
    otherResponses.add(1);
  }

  check(response, {
    'gateway returns stub response': (res) => res.status === 200 && res.json('status') === 'stub',
    'gateway returns W3C trace context': (res) => {
      const traceparent = responseHeader(res.headers, 'traceparent');
      const fields = traceparent.split('-');
      return fields.length === 4
        && fields[0] === '00'
        && fields[1] === traceId
        && /^[0-9a-f]{16}$/i.test(fields[2])
        && fields[2] !== spanId
        && fields[3] === '01';
    },
    'gateway propagates W3C trace context upstream': (res) => {
      if (res.status !== 200 || !res.body) {
        return false;
      }
      let traceparent;
      try {
        traceparent = res.json('traceparent') || '';
      } catch {
        return false;
      }
      const fields = traceparent.split('-');
      return fields.length === 4
        && fields[0] === '00'
        && fields[1] === traceId
        && /^[0-9a-f]{16}$/i.test(fields[2])
        && fields[2] !== spanId
        && fields[3] === '01';
    },
  });

  const elapsedSeconds = (Date.now() - iterationStartedAt) / 1000;
  sleep(Math.max(iterationIntervalSeconds - elapsedSeconds, 0));
}
