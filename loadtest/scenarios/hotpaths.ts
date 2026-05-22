import { sleep, check } from 'k6';
import exec from 'k6/execution';
import { login, authedGet, authedPost } from './lib/auth';
import { tenantForVu } from './lib/manifest';

// Hot-path scenario: the CPU-heavy projection/optimize endpoints — the primary
// profiling target. Each VU logs in, lists its scenarios, picks one, then runs:
//   - the deterministic projection run            GET  /api/v1/projections/{id}/run
//   - the Monte Carlo guardrail optimization      POST /api/v1/projections/{id}/optimize
//   - the Roth-conversion optimization (same endpoint, optimizeConversions=true)
//
// The guardrail-optimize endpoint accepts a minimal body — every field on
// GuardrailOptimizationRequest is nullable and GuardrailProfileService.optimize
// fills in graceful defaults (trial count, return mean, exhaustion buffer, etc.),
// so an empty object drives full Monte Carlo compute. Setting optimizeConversions
// to true additionally engages the Roth-conversion optimizer.
export default function (): void {
  const tenant = tenantForVu(exec.vu.idInTest);
  const session = login(tenant);

  // ProjectionController.list returns a plain List<ScenarioResponse> (not a paged
  // envelope), each element carrying a UUID `id`.
  const list = authedGet('/api/v1/projections', 'scenario_list');
  check(list, { 'scenarios 200': (r) => r.status === 200 });
  const scenarios = list.json() as Array<{ id: string }>;
  if (!scenarios || scenarios.length === 0) {
    return;
  }
  const sid = scenarios[0].id;

  const proj = authedGet(`/api/v1/projections/${sid}/run`, 'projection_run');
  check(proj, { 'projection_run 200': (r) => r.status === 200 });

  const mc = authedPost(`/api/v1/projections/${sid}/optimize`, {}, 'mc_optimize', session);
  check(mc, { 'mc_optimize 200': (r) => r.status === 200 });

  const roth = authedPost(
    `/api/v1/projections/${sid}/optimize`,
    { optimizeConversions: true },
    'roth_optimize',
    session,
  );
  check(roth, { 'roth_optimize 200': (r) => r.status === 200 });

  sleep(1);
}
