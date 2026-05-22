import type { Options } from 'k6/options';
import { VUS_MAX } from './lib/config';

// Expose the scenario bodies as named execs for k6.
export { default as baseline } from './baseline';
export { default as hotpaths } from './hotpaths';

export const options: Options = {
  scenarios: {
    read_baseline: {
      executor: 'ramping-vus',
      exec: 'baseline',
      startVUs: 0,
      stages: [
        { duration: '1m', target: Math.max(1, Math.round(VUS_MAX * 0.1)) },
        { duration: '2m', target: Math.max(1, Math.round(VUS_MAX * 0.25)) },
        { duration: '2m', target: Math.max(1, Math.round(VUS_MAX * 0.5)) },
        { duration: '2m', target: VUS_MAX },
        { duration: '1m', target: 0 },
      ],
    },
    hot_paths: {
      executor: 'ramping-vus',
      exec: 'hotpaths',
      startVUs: 0,
      stages: [
        { duration: '2m', target: Math.max(1, Math.round(VUS_MAX * 0.1)) },
        { duration: '4m', target: Math.max(1, Math.round(VUS_MAX * 0.25)) },
        { duration: '1m', target: 0 },
      ],
    },
  },
  // Recorded as observations, not pass/fail gates.
  thresholds: {
    'http_req_duration{name:projection_run}': ['p(95)<2000'],
    'http_req_failed': ['rate<0.05'],
  },
};
