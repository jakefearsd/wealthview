import { sleep, check } from 'k6';
import exec from 'k6/execution';
import { loginOnce, authedGet, authedPost } from './lib/auth';
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
  loginOnce(tenant);

  // ProjectionController.list returns a plain List<ScenarioResponse> (not a paged
  // envelope), each element carrying a UUID `id`.
  const list = authedGet('/api/v1/projections', 'scenario_list');
  check(list, { 'scenarios 200': (r) => r.status === 200 });
  const scenarios = list.json() as Array<{ id: string }>;
  // Guard against a non-array body (e.g. an error envelope if a request was
  // throttled or auth lapsed) so the VU skips the iteration instead of throwing.
  if (!Array.isArray(scenarios) || scenarios.length === 0 || !scenarios[0]?.id) {
    return;
  }
  const sid = scenarios[0].id;

  const proj = authedGet(`/api/v1/projections/${sid}/run`, 'projection_run');
  check(proj, { 'projection_run 200': (r) => r.status === 200 });

  const mc = authedPost(`/api/v1/projections/${sid}/optimize`, {}, 'mc_optimize');
  check(mc, { 'mc_optimize 200': (r) => r.status === 200 });

  const roth = authedPost(
    `/api/v1/projections/${sid}/optimize`,
    { optimizeConversions: true },
    'roth_optimize',
  );
  check(roth, { 'roth_optimize 200': (r) => r.status === 200 });

  sleep(1);
}
