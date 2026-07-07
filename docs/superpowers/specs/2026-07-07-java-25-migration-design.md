# Java 25 Migration — Design

**Date:** 2026-07-07
**Status:** Approved (design) — pending implementation plan
**Scope:** Backend only (`backend/` Maven multi-module). Mobile, frontend, and shared are explicitly untouched.

---

## 1. Goal & End State

Migrate the WealthView backend from **Java 21 → Java 25** (LTS), riding along a **Spring Boot 3.5.16 → 4.1.x** upgrade and an API-level idiom modernization sweep.

**End state:**

- Backend compiles and runs at **language level 25** on a **JDK 25** toolchain (build image + runtime image + CI).
- **Spring Boot 4.1.x** — Spring Framework 7, Jakarta EE 11, Hibernate ORM 7, Jackson 3.
- All five enforced quality gates (PMD, CPD, SpotBugs, Checkstyle, JaCoCo) green on `mvn verify`.
- Byte Buddy / Mockito support class-file v69 so the full unit + integration suite passes on JDK 25.
- **Mobile stays on JDK 17** (React Native 0.85.x requirement, already fenced off in `mobile/.sdkmanrc` / `mobile/android/.sdkmanrc`). Frontend/shared are TypeScript — irrelevant.

## 2. Non-Goals

- No functional/feature changes; behavior is preserved throughout.
- No changes to the mobile, frontend, or shared workspaces.
- No adoption of Java 22–25 **new-syntax** language features (see Decision 3).
- No preview features enabled.
- Deployment mechanics (`./wv update`) unchanged.

## 3. Current State (baseline)

| Thing | Current |
|---|---|
| Language level | `<java.version>21</java.version>` |
| Spring Boot parent | `3.5.16` (Spring Framework 6.2, Jakarta EE 10) |
| Backend Java files | ~701 |
| Preview features | none |
| Removed/degraded-API usage (SecurityManager, finalizers, `sun.misc.Unsafe`, `Thread.stop`) | none found |
| `javax.*` stragglers | none (already Jakarta) |
| Security config | lambda DSL (`authorizeHttpRequests`), but uses `AntPathRequestMatcher` (removed in Spring Security 7) in 6 sites |
| Jackson | `com.fasterxml.jackson.*` across 31 files; custom SNAKE_CASE `ObjectMapper`, `@JsonNaming`/`@JsonUnwrapped`, JSONB serialization |
| JaCoCo | `0.8.15` (**already Java-25-ready**) |
| PMD plugin | `maven-pmd-plugin 3.28.0`, `targetJdk 21` |
| SpotBugs plugin | `spotbugs-maven-plugin 4.10.2.0` |
| Checkstyle plugin | `maven-checkstyle-plugin 3.6.0` |
| PIT | `pitest-maven 1.25.5` (advisory, not a gate) |
| Testcontainers | `2.0.5` |
| Version pin sites | `backend/pom.xml`, `Dockerfile` (build + runtime images, digest-pinned), `.github/workflows/backend-verify.yml` |

## 4. Decisions

1. **Sequencing — Approach A: JDK 25 first, then Spring Boot 4.1.**
   The JDK bump is low-risk (clean code, no removed-API usage, JaCoCo already ready, only Byte Buddy needs attention); the Boot 3.5→4.1 jump is high-risk (Jackson 3, Security 7, Hibernate 7, config renames, jar modularization). We isolate one variable at a time. Doing JDK 25 first delivers the headline goal early, de-risks it against known-good code, and lands the high-risk Boot-4 commit **on top of** a green JDK-25 base so it is independently revertible without unwinding the JDK bump. Accepted cost: a throwaway 2-line Byte Buddy/Mockito pin during Phase 1, removed in Phase 2.
   - *Rejected:* Approach C (Boot-4 first) — elegant (zero throwaway pin) but delays JDK 25 behind the big lift and makes the risky change the base. Approach B (big-bang both) — rejected outright; un-bisectable.

2. **Jackson — staged: bridge during Boot-4, then a dedicated 2→3 phase.**
   During Phase 2 keep Jackson 2 semantics via `spring.jackson.use-jackson2-defaults=true` (+ the deprecated `spring-boot-jackson2` shim) so the framework upgrade is isolated from the serialization change. Phase 3 is a dedicated `com.fasterxml.jackson.* → tools.jackson.*` migration across the 31 files, then the shim/flag are removed.
   - *Rejected:* migrating straight to Jackson 3 inside Phase 2 — conflates two large changes in one commit.

3. **Idiom sweep — API-level features only (no new syntax) for now.**
   Checkstyle and PMD do not yet parse Java 23–25 grammar (compact source files, flexible constructor bodies, module-import declarations). Introducing new *syntax* would break the enforced gates. Restrict Phase 4 to API-level improvements (`Math.clamp`, `SequencedCollection.reversed()`, `Stream.gather`, scoped values where they fit). Syntax-level idioms are deferred until the parsers catch up.

4. **PIT (mutation testing) must work on JDK 25.**
   Although PIT is advisory (not a `mvn verify` gate), it is kept functional. Phase 0 bumps it to a JDK-25-capable release (sanity-checked on JDK 21); Phase 1 verifies a `mutationCoverage` run completes on JDK 25. The **only** sanctioned fallback is to temporarily treat PIT as advisory-lag — and only if upstream JDK-25 support proves a genuine blocking difficulty, documented in the commit.

## 5. Target Versions

Resolve each to the latest patch available at implementation time; the values below are floors driven by Java-25 support.

| Component | Target | Rationale |
|---|---|---|
| Language level / toolchain | **Java 25** | The migration goal (class-file v69). |
| Spring Boot | **4.1.x** (≥ 4.1.0) | First-class JDK 25/26; SF7 / Jakarta EE 11; latest JDK-25-certified line. SF 6.2 OSS support ended June 2026. |
| Byte Buddy | **≥ 1.17.5** | Class-file v69 support. Manual pin in Phase 1; provided transitively by the Boot 4.1 BOM from Phase 2 on. |
| Mockito | **≥ 5.16** | JDK 25 support (paired with Byte Buddy ≥ 1.17.5). |
| PMD core (`<pmdVersion>`) | **≥ 7.16.0** | Java 25 language support. |
| `maven-pmd-plugin` | keep 3.28.0 (bump if needed); `targetJdk` → 25 | Java 25 target. |
| SpotBugs core | **≥ 4.9.7** | BCEL 6.11 → class-file v69. |
| Checkstyle core | latest **13.x** | Parses current Java-21-level code; keeps up with tooling. |
| JaCoCo | **0.8.15** (unchanged) | Already Java-25-ready. |
| PIT | latest with JDK-25 support | Kept working (verified on JDK 25 in Phase 1). Not a `mvn verify` gate, but must run. Advisory-lag fallback **only** if upstream JDK-25 support is a blocking difficulty. |
| Build image | `maven:3-eclipse-temurin-25` (digest-pinned) | JDK 25 build. |
| Runtime image | `eclipse-temurin:25-jre-alpine` (digest-pinned) | JDK 25 runtime. |
| CI `setup-java` | `25` | JDK 25 CI. |

Bundled-by-Boot-4.1 (verify, don't pin unless needed): Spring Framework 7, Hibernate ORM 7, Jakarta EE 11 APIs, Jackson 3.

## 6. Phased Plan

Each phase ends with a **gate**: full `mvn verify` green **run locally** (CI skips the app-module Failsafe ITs on GitHub runners), then one logical commit. Phases are independently revertible.

### Phase 0 — Quality-tool readiness (may precede everything)
- PMD: pin core `<pmdVersion>` ≥ 7.16.0; set `targetJdk` → 25 in both the build execution and the reporting block.
- SpotBugs: pin core ≥ 4.9.7.
- Checkstyle: bump to latest 13.x.
- JaCoCo: no change (0.8.15).
- PIT: bump to the latest release that targets JDK 25; confirm the `mutationCoverage` run still completes on JDK 21 (strict JDK-25 verification happens in Phase 1).
- **Gate:** `mvn verify` still green on JDK 21 (tools bumped, language unchanged).

### Phase 1 — JDK 21 → 25 (still Spring Boot 3.5.16)
- `backend/pom.xml`: `<java.version>25</java.version>`; Javadoc `<source>` → 25; **temporary** `<byte-buddy.version>` (≥ 1.17.5) and, if needed, `<mockito.version>` (≥ 5.16) overrides.
- `Dockerfile`: build stage → `maven:3-eclipse-temurin-25` (re-pin digest); runtime stage → `eclipse-temurin:25-jre-alpine` (re-pin digest). Refresh the digest-pin comments.
- `.github/workflows/backend-verify.yml`: `setup-java` `java-version: '25'`.
- **PIT (strict):** run `mvn -q test-compile org.pitest:pitest-maven:mutationCoverage -pl wealthview-core,wealthview-projection` on JDK 25; it must complete successfully. If upstream PIT genuinely cannot support JDK 25 yet, document the blocker in the commit and temporarily treat PIT as advisory-lag — the only sanctioned exception (Decision 4).
- **Gate:** full `mvn verify` (incl. Failsafe ITs, locally) green on JDK 25 with Boot 3.5.16. Commit.

### Phase 2 — Spring Boot 3.5.16 → 4.1.x (on JDK 25)
- Parent `<version>` → 4.1.x; **remove** the temporary Byte Buddy/Mockito pins (BOM now supplies them).
- **Spring Security 7:** `AntPathRequestMatcher` → `PathPatternRequestMatcher` in `SecurityConfig` (6 sites, lines ~70–75); re-verify the CSRF ignore matchers and role rules behave identically.
- **Config properties:** add `spring-boot-properties-migrator` temporarily; run app across `application*.yml` profiles (dev/docker/it/prod), fix renamed/removed keys, then remove the migrator.
- **Hibernate 7 / Jakarta EE 11:** verify `@JdbcTypeCode(SqlTypes.JSON)` JSONB mapping (`params_json`, `spending_tiers`, etc.) and any deprecated Hibernate APIs still compile and behave.
- **Jackson bridge:** set `spring.jackson.use-jackson2-defaults=true` (+ `spring-boot-jackson2` shim) to keep serialization behavior fixed while the framework changes.
- **Starters / module split:** confirm all `spring-boot-starter-*` artifacts still resolve under Boot 4's modularization; adjust any renamed starters.
- Verify module-by-module in dependency order: persistence → core → api/import/projection → app.
- **Gate:** `mvn verify` green (incl. ITs, locally). Commit.

### Phase 3 — Jackson 2 → 3
- Rewrite imports `com.fasterxml.jackson.{databind,core,...}` → `tools.jackson.*` across the 31 files. **Annotations stay** on `com.fasterxml.jackson.annotation.*`.
- Migrate the custom `ObjectMapper`/`JsonMapper` builder, `TypeReference`, `SerializationFeature`, `PropertyNamingStrategies`. Note Jackson 3 throws **unchecked** exceptions — remove now-invalid `throws JsonProcessingException`/catch clauses where the compiler flags them.
- Confirm SNAKE_CASE global strategy, `@JsonNaming`, `@JsonInclude`, `@JsonUnwrapped`, `@JsonIgnoreProperties` still produce identical wire JSON (JSON-response contract is snake_case — assert via existing controller/IT tests).
- Remove the `spring-boot-jackson2` shim and `use-jackson2-defaults` flag.
- **Gate:** `mvn verify` green (JSON-contract tests are the safety net). Commit.

### Phase 4 — Idiom sweep (API-level only)
- Module-by-module, behavior-preserving, small reviewable commits.
- Allowed: `Math.clamp`, `SequencedCollection`/`List.reversed()`, `Stream.gather`, scoped values where they cleanly fit, and other **API-level** Java 22–25 additions.
- Forbidden (for now): flexible constructor bodies, module-import declarations, compact source files, and any other **new syntax** Checkstyle/PMD can't parse.
- Update `pmd-ruleset.xml`/config language-level notes as needed.
- Every change stays under existing tests + the five gates.
- **Gate:** `mvn verify` green after each commit.

## 7. Risks & Mitigations

- **Boot-4 test-runtime drift (Mockito/Byte Buddy):** biggest risk to the suite. Mitigated by phase isolation and BOM-provided versions from Phase 2.
- **Jackson 3 wire-format drift:** the snake_case JSON contract must not change. Mitigated by the staged bridge (Phase 2) + dedicated migration (Phase 3), gated by existing controller/IT JSON assertions.
- **Spring Security 7 matcher swap:** `PathPatternRequestMatcher` pattern semantics differ subtly from Ant patterns for edge cases — re-verify auth/CSRF ITs.
- **CI does not run app-module ITs** (`-DskipITs` on GitHub runners, per existing note): **run `mvn verify` locally at every phase gate** before committing/tagging.
- **PIT on JDK 25:** required to work (verified in Phase 1's PIT step). Not a `mvn verify` gate, but must run successfully. Documented escape hatch: revert to advisory-lag only if upstream JDK-25 support is a genuine blocking difficulty.
- **Checkstyle/PMD grammar lag:** contained by Decision 3 (no new syntax).

## 8. Verification Strategy

- Per-phase gate = local `mvn verify` (unit + `@DataJpaTest` + Failsafe app ITs + all five gates).
- Coverage floors (CLAUDE.md) must hold: core/projection line ≥ 0.90; api/import ≥ 0.80; branch floors core 0.83 / projection 0.84 / api 0.85 / import 0.71. Never lowered.
- Smoke-run the app (`./wv up`) after Phase 2 and Phase 3 to confirm boot + health + a login/dashboard round-trip.
- Docker image build must succeed (mirrors the CI release step).

## 9. Rollback

Each phase is a single logical commit on the current branch (no feature branch, per project policy). Revert any phase independently. Under Approach A the JDK-25 base survives a Boot-4 revert.

## 10. Open Items to Resolve During Implementation

- Exact latest patch versions (Spring Boot 4.1.x, PMD, SpotBugs, Checkstyle, Byte Buddy, Mockito, PIT) at implementation time.
- Whether `maven-pmd-plugin 3.28.0` needs a plugin bump (vs. only a `<pmdVersion>` override) to honor `targetJdk 25`.
- Whether Boot 4.1's managed Testcontainers version conflicts with the explicit `2.0.5` pin (align if so).
- Confirm the `eclipse-temurin:25-jre-alpine` runtime image exists and pick the pinned digest; same for `maven:3-eclipse-temurin-25`.
