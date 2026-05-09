# @wealthview/shared

Cross-platform utilities and types shared between the WealthView web frontend (Vite + React) and mobile app (React Native + Metro). Both consumers import this package directly as TypeScript source — there is no build step. Vite and Metro each transpile the source for their own target.

## Conventions

- **Pure functions only.** No side effects at import time.
- **No platform-only imports.** Do not import from the DOM (`window`, `document`, `navigator`), Node-only modules (`fs`, `path`), or React. Anything platform-specific belongs in the consumer.
- **Type-first.** Prefer exported `type` / `interface` declarations alongside the runtime helpers that use them. Future schema-typed API contracts live here.
- **Tests are co-located** (`format.ts` + `format.test.ts`) and run with Vitest.

Adding code here automatically becomes available to both `frontend/` and `mobile/`. A change to a finance-domain helper propagates to both clients with no copy.
