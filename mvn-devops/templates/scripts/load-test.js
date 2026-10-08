// The load test load-test.sh runs when the project has none of its own
// (LOAD_TEST_SCRIPT): VUS virtual users call each of PATHS on BASE_URL for
// DURATION, with a second's pause between rounds.  It fails when more than
// MAX_ERROR_RATE of the requests fail or the 95th percentile of the response
// times is above P95_MS milliseconds.
import http from 'k6/http';
import { check, sleep } from 'k6';

const base = __ENV.BASE_URL;
const paths = (__ENV.PATHS || '/actuator/health').split(',');

export const options = {
  scenarios: {
    load: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_failed: [`rate<${__ENV.MAX_ERROR_RATE || 0.01}`],
    http_req_duration: [`p(95)<${__ENV.P95_MS || 500}`],
    checks: ['rate>0.99'],
  },
};

export default function () {
  for (const path of paths) {
    const res = http.get(base + path, { tags: { name: path } });
    check(res, { 'status is 2xx': (r) => r.status >= 200 && r.status < 300 });
  }
  sleep(1);
}
