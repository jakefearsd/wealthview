import { sleep, check } from 'k6';
import exec from 'k6/execution';
import { loginOnce, authedGet } from './lib/auth';
import { tenantForVu } from './lib/manifest';

// Read-baseline scenario: each VU authenticates once (reusing the session on
// later iterations) then exercises the cheap read endpoints (accounts list +
// dashboard summary + portfolio history).
export default function (): void {
  const tenant = tenantForVu(exec.vu.idInTest);
  loginOnce(tenant);

  const accounts = authedGet('/api/v1/accounts', 'accounts');
  check(accounts, { 'accounts 200': (r) => r.status === 200 });

  const summary = authedGet('/api/v1/dashboard/summary', 'dashboard_summary');
  check(summary, { 'dashboard_summary 200': (r) => r.status === 200 });

  const history = authedGet('/api/v1/dashboard/portfolio-history', 'portfolio_history');
  check(history, { 'portfolio_history 200': (r) => r.status === 200 });

  sleep(1);
}
