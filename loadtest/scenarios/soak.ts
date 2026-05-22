import type { Options } from 'k6/options';
import { VUS_MAX } from './lib/config';

export { default as hotpaths } from './hotpaths';

export const options: Options = {
  scenarios: {
    hot_paths_soak: {
      executor: 'constant-vus',
      exec: 'hotpaths',
      vus: Math.max(5, Math.round(VUS_MAX * 0.15)),
      duration: __ENV.SOAK_DURATION || '15m',
    },
  },
  thresholds: { 'http_req_failed': ['rate<0.02'] },
};
