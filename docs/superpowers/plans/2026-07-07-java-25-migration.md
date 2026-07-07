# Java 25 Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the WealthView backend from Java 21 to Java 25, upgrading Spring Boot 3.5.16 → 4.1.x and modernizing to API-level Java idioms, with all quality gates green throughout.

**Architecture:** Five strictly-sequential phases, each ending in a local `mvn verify` gate and one commit. Approach A ordering: land JDK 25 first on the current Spring Boot (isolating the low-risk toolchain change against known-good code), then land the high-risk Spring Boot 4.1 upgrade *on top of* a green JDK-25 base so it is independently revertible. Jackson 2→3 and the idiom sweep follow as their own phases.

**Tech Stack:** Java 25 (Temurin), Spring Boot 4.1.x (Spring Framework 7 / Jakarta EE 11 / Hibernate ORM 7 / Jackson 3), Maven multi-module, Testcontainers + PostgreSQL 16, quality gates PMD/CPD/SpotBugs/Checkstyle/JaCoCo, PIT mutation testing.

**Spec:** `docs/superpowers/specs/2026-07-07-java-25-migration-design.md`

## Global Constraints

Every task inherits these. Values are copied verbatim from the spec.

- **Backend only.** Do not touch `mobile/` (stays JDK 17), `frontend/`, or `shared/`.
- **Language/toolchain target:** Java 25 (class-file v69).
- **Spring Boot target:** 4.1.x (≥ 4.1.0) — resolve to latest patch at implementation time.
- **Version floors** (resolve to latest patch ≥ floor at implementation time): Byte Buddy ≥ 1.17.5, Mockito ≥ 5.16, PMD core ≥ 7.16.0, SpotBugs core ≥ 4.9.7, Checkstyle core latest 13.x. JaCoCo stays 0.8.15 (already Java-25-ready — do not change).
- **No new Java 23–25 *syntax*** (flexible constructor bodies, module-import declarations, compact source files). Checkstyle/PMD cannot parse it and the gates would break. API-level features only.
- **PIT must work on JDK 25** (verified in Phase 1). Advisory-lag fallback only if upstream support is a genuine blocking difficulty, documented in the commit.
- **Run `mvn verify` LOCALLY at every phase gate** (incl. app-module Failsafe ITs) — CI runs `-DskipITs` on GitHub runners. Docker must be running for Testcontainers.
- **Coverage floors must hold, never lowered:** line — core/projection ≥ 0.90, api/import ≥ 0.80; branch — core 0.83, projection 0.84, api 0.85, import 0.71.
- **Commit on the current branch.** No feature branches, no worktrees (project policy).
- **Never push** without an explicit instruction.
- Every gate = one logical commit per phase; phases are independently revertible.

---

## Prerequisite Task: Install & verify the JDK 25 toolchain

**Files:**
- Create: `backend/.sdkmanrc`

**Interfaces:**
- Produces: a working `java 25` on PATH for `backend/`; a `.sdkmanrc` that auto-activates it (mirrors the `mobile/.sdkmanrc` pattern, and satisfies the assumption already written in `mobile/.sdkmanrc`'s comment that "the repo root uses JDK 25 for backend").

- [ ] **Step 1: Install a Temurin 25 JDK via SDKMAN**

Run: `sdk list java | grep -i '25\.' | grep -i tem` to find the latest Temurin 25 identifier (e.g. `25.0.x-tem`), then:
Run: `sdk install java 25.0.x-tem` (substitute the actual identifier).

- [ ] **Step 2: Verify the JDK is available**

Run: `sdk use java 25.0.x-tem && java -version`
Expected: output shows `openjdk version "25...` / `Temurin`.

- [ ] **Step 3: Create `backend/.sdkmanrc` so the backend auto-activates JDK 25**

```
# WealthView backend targets JDK 25 (Temurin). SDKMAN auto-switches when you
# cd into this directory if sdkman_auto_env=true. Mobile stays on JDK 17
# (see mobile/.sdkmanrc) — this pin is backend-only.
java=25.0.x-tem
```
(Substitute the identifier installed in Step 1.)

- [ ] **Step 4: Confirm the current backend build is green on JDK 21 first (baseline)**

Leave the toolchain on JDK 21 for Phase 0 (do NOT switch yet — Phase 0 changes only tool versions). Confirm Docker is up for later IT runs:
Run: `docker compose up -d db && docker ps`
Expected: the `db` container is running.

No commit (environment setup only; `.sdkmanrc` is committed in Task 2).

---

## Task 1 — Phase 0: Bump quality-tool cores to JDK-25-capable versions (still on JDK 21)

Bump the *versions* of PMD, SpotBugs, Checkstyle, and PIT so they can parse/analyze class-file v69 later, while the language level stays at 21. This isolates "tool version churn" from "the language flip." JaCoCo is already 0.8.15 (Java-25-ready) — untouched.

**Files:**
- Modify: `backend/pom.xml` (properties block ~85–88; PMD plugin config ~296–332 and reporting ~585–597; SpotBugs plugin ~253–273; Checkstyle plugin ~274–295; PIT plugin ~333–364)

**Interfaces:**
- Produces: quality gates that run on JDK-25-capable tool cores; `mvn verify` still green on **JDK 21**.

- [ ] **Step 1: Ensure the toolchain is JDK 21 for this phase**

Run: `sdk use java 21.0.x-tem && java -version`
Expected: `openjdk version "21...`. (Phase 0 must be validated on 21.)

- [ ] **Step 2: Add core-version properties**

In `backend/pom.xml`, in the `<properties>` block right after the existing quality-tool versions (~line 88), add:

```xml
        <!-- Quality-tool CORE versions overridden to Java-25-capable releases.
             Plugin versions stay; only the analysis engine they embed is pinned. -->
        <pmd.core.version>7.16.0</pmd.core.version>
        <spotbugs.core.version>4.9.7</spotbugs.core.version>
        <checkstyle.core.version>13.7.0</checkstyle.core.version>
```
Resolve each to the latest patch ≥ the floor at implementation time (`sdk`/Maven Central check). Bump `checkstyle-plugin.version` (line 86) to the latest `maven-checkstyle-plugin` (≥ 3.6.0) if a newer one is available.

- [ ] **Step 3: Pin PMD core in the build plugin config**

In the `maven-pmd-plugin` `<configuration>` inside `<pluginManagement>` (the block starting ~line 300), keep `<targetJdk>21</targetJdk>` for now and add a `<pmdVersion>`:

```xml
                    <configuration>
                        <targetJdk>21</targetJdk>
                        <pmdVersion>${pmd.core.version}</pmdVersion>
                        <failOnViolation>true</failOnViolation>
                        <printFailingErrors>true</printFailingErrors>
                        <linkXRef>true</linkXRef>
                        <rulesets>
                            <ruleset>pmd-ruleset.xml</ruleset>
                        </rulesets>
                    </configuration>
```

Do the same in the `<reporting>` PMD block (~line 589): add `<pmdVersion>${pmd.core.version}</pmdVersion>` next to `<targetJdk>21</targetJdk>`.

- [ ] **Step 4: Pin SpotBugs core via a plugin dependency**

In the `spotbugs-maven-plugin` block inside `<pluginManagement>` (~line 253), add a `<dependencies>` element that overrides the embedded core (place it as a sibling of `<configuration>`/`<executions>`):

```xml
                    <dependencies>
                        <dependency>
                            <groupId>com.github.spotbugs</groupId>
                            <artifactId>spotbugs</artifactId>
                            <version>${spotbugs.core.version}</version>
                        </dependency>
                    </dependencies>
```

- [ ] **Step 5: Pin Checkstyle core via a plugin dependency**

In the `maven-checkstyle-plugin` block inside `<pluginManagement>` (~line 274), add:

```xml
                    <dependencies>
                        <dependency>
                            <groupId>com.puppycrawl.tools</groupId>
                            <artifactId>checkstyle</artifactId>
                            <version>${checkstyle.core.version}</version>
                        </dependency>
                    </dependencies>
```

- [ ] **Step 6: Bump PIT to a JDK-25-capable release**

In the `pitest-maven` plugin block (~line 334), bump `<version>1.25.5</version>` to the latest `pitest-maven` and `pitest-junit5-plugin` `<version>1.2.3</version>` to the latest. Check Maven Central / the pitest releases page for the newest that advertises JDK-25 support. Prefer extracting the versions to properties for clarity:

```xml
        <pitest-maven.version>LATEST_PITEST</pitest-maven.version>
        <pitest-junit5.version>LATEST_PITEST_JUNIT5</pitest-junit5.version>
```
and reference them in the plugin. (Substitute the resolved versions — do not leave the literal `LATEST_*` tokens.)

- [ ] **Step 7: Verify the gates still pass on JDK 21**

Run: `mvn -f backend/pom.xml clean verify -DskipITs -B`
Expected: BUILD SUCCESS; PMD, CPD, SpotBugs, Checkstyle, JaCoCo all pass with the new cores.

- [ ] **Step 8: Verify PIT still runs on JDK 21 (sanity, pre-JDK-25)**

Run: `mvn -f backend/pom.xml -q test-compile org.pitest:pitest-maven:mutationCoverage -pl wealthview-core,wealthview-projection`
Expected: PIT completes and writes `target/pit-reports/`. (Strict JDK-25 PIT check is Task 2 Step 8.)

- [ ] **Step 9: Commit**

```bash
git add backend/pom.xml
git commit -m "chore(build): bump quality-tool cores to JDK-25-capable versions

Pin PMD core >=7.16.0, SpotBugs core >=4.9.7, Checkstyle core (latest
13.x), and bump PIT so the analysis engines understand class-file v69
ahead of the language flip. Plugin versions unchanged except checkstyle;
language level stays 21. JaCoCo already 0.8.15 (Java-25-ready). Gates and
a PIT mutationCoverage run stay green on JDK 21.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013zqLcvSyE7cr1g5Z5m8zio"
```

---

## Task 2 — Phase 1: Flip the language level & toolchain to Java 25 (still Spring Boot 3.5.16)

Move compilation, PMD target, Javadoc source, Docker images, and CI to JDK 25. Add a **temporary** Byte Buddy/Mockito pin (Boot 3.5.16's BOM ships pre-1.17.5 Byte Buddy, which cannot instrument class-file v69). This pin is removed in Task 3.

**Files:**
- Modify: `backend/pom.xml` (line 73 `<java.version>`; PMD `targetJdk` lines 301 & 590; Javadoc `<source>` line 486; `<properties>` — add Byte Buddy/Mockito overrides)
- Modify: `Dockerfile` (build image line 22–23; runtime image line 37–38)
- Modify: `.github/workflows/backend-verify.yml` (line 21–26 `Set up JDK`)
- Add: `backend/.sdkmanrc` (created in the Prerequisite Task — commit it here)

**Interfaces:**
- Consumes: JDK-25-capable quality tools from Task 1.
- Produces: a backend that compiles at `--release 25`, runs on a JDK 25 runtime image, with green `mvn verify` (incl. ITs) and a working PIT run on JDK 25. Byte Buddy/Mockito pinned ≥ floors (temporary).

- [ ] **Step 1: Switch the active toolchain to JDK 25**

Run: `sdk use java 25.0.x-tem && java -version`
Expected: `openjdk version "25...`.

- [ ] **Step 2: Set the language level to 25 in `backend/pom.xml`**

Change line 73:
```xml
        <java.version>25</java.version>
```
(The Spring Boot parent maps `java.version` to `maven.compiler.release`, so this sets `--release 25`.)

- [ ] **Step 3: Move PMD target and Javadoc source to 25**

Change both PMD `<targetJdk>21</targetJdk>` occurrences (lines 301 and 590) to:
```xml
                        <targetJdk>25</targetJdk>
```
Change the Javadoc `<source>21</source>` (line 486) to:
```xml
                    <source>25</source>
```

- [ ] **Step 4: Add the temporary Byte Buddy / Mockito override**

In `<properties>` (after the core-version props added in Task 1), add:
```xml
        <!-- TEMPORARY (removed in the Spring Boot 4.1 upgrade): Boot 3.5.16's
             managed Byte Buddy predates class-file v69 support, so Mockito
             cannot mock under JDK 25. Pin forward until the Boot 4.1 BOM
             supplies these. -->
        <byte-buddy.version>1.17.5</byte-buddy.version>
        <mockito.version>5.16.0</mockito.version>
```
Resolve to the latest patch ≥ floor. (`byte-buddy.version` and `mockito.version` are the exact property names the Spring Boot parent uses, so these override the managed versions.)

- [ ] **Step 5: Update the Dockerfile build & runtime images to JDK 25**

In `Dockerfile`, change the build stage (lines 22–23):
```dockerfile
# Pinned by digest (maven:3.9-eclipse-temurin-25). To upgrade: `docker pull
# maven:3.9-eclipse-temurin-25` then `docker inspect --format='{{index .RepoDigests 0}}' maven:3.9-eclipse-temurin-25`.
FROM maven:3.9-eclipse-temurin-25@sha256:<RESOLVE_DIGEST> AS build
```
and the runtime stage (lines 37–38):
```dockerfile
# Pinned by digest (eclipse-temurin:25-jre-alpine).
FROM eclipse-temurin:25-jre-alpine@sha256:<RESOLVE_DIGEST>
```
Resolve `<RESOLVE_DIGEST>` for each by running (locally, with Docker):
Run: `docker pull maven:3.9-eclipse-temurin-25 && docker inspect --format='{{index .RepoDigests 0}}' maven:3.9-eclipse-temurin-25`
Run: `docker pull eclipse-temurin:25-jre-alpine && docker inspect --format='{{index .RepoDigests 0}}' eclipse-temurin:25-jre-alpine`
If a `maven:3.9-eclipse-temurin-25` tag does not exist, use `maven:3-eclipse-temurin-25` (rolling 3.x). Do not leave the `<RESOLVE_DIGEST>` placeholder.

- [ ] **Step 6: Update CI to JDK 25**

In `.github/workflows/backend-verify.yml`, change the `Set up JDK` step (lines 21–26):
```yaml
      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
          cache: maven
```

- [ ] **Step 7: Compile and run the full verify on JDK 25**

Run: `mvn -f backend/pom.xml clean verify -B`
Expected: BUILD SUCCESS. All Surefire unit tests, persistence `@DataJpaTest`, the app-module Failsafe ITs, and all five gates pass on JDK 25. (Docker must be running.)
If Mockito-based tests fail with "Unsupported class file major version 69" or Byte Buddy errors, re-check Step 4 versions.

- [ ] **Step 8: Verify PIT runs on JDK 25 (strict — Decision 4)**

Run: `mvn -f backend/pom.xml -q test-compile org.pitest:pitest-maven:mutationCoverage -pl wealthview-core,wealthview-projection`
Expected: PIT completes and writes `target/pit-reports/`.
If upstream PIT genuinely cannot run on JDK 25 yet, note the exact error in the commit body and proceed treating PIT as advisory-lag — this is the only sanctioned exception.

- [ ] **Step 9: Build the release Docker image (mirrors CI release step)**

Run: `docker build -t wealthview:java25-check .`
Expected: image builds successfully on the JDK 25 stages.

- [ ] **Step 10: Smoke-test the running app**

Run: `./wv up` then, once healthy, `./wv status`
Expected: container healthy; then log in at http://localhost:80 with `demo@wealthview.local` / `demo123` and confirm the dashboard loads. Run `./wv down` after.

- [ ] **Step 11: Commit**

```bash
git add backend/pom.xml backend/.sdkmanrc Dockerfile .github/workflows/backend-verify.yml
git commit -m "build: compile and run the backend on Java 25

Flip <java.version> to 25 (release 25), PMD targetJdk and Javadoc source
to 25, the Docker build/runtime images to eclipse-temurin 25, and CI
setup-java to 25. Add a temporary Byte Buddy/Mockito pin so Mockito can
instrument class-file v69 under Boot 3.5.16 (removed in the Boot 4.1
upgrade). Full mvn verify (incl. Failsafe ITs) and a PIT mutationCoverage
run are green on JDK 25; the release image builds. Mobile stays JDK 17.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013zqLcvSyE7cr1g5Z5m8zio"
```

---

## Task 3 — Phase 2: Upgrade Spring Boot 3.5.16 → 4.1.x (on JDK 25)

The high-risk phase: Spring Framework 7, Jakarta EE 11, Hibernate ORM 7, Spring Security 7, jar modularization. Jackson stays on 2-compatible behavior via a shim (Jackson 3 migration is Task 4). Work module-by-module; the safety net is the existing test suite + gates.

**Files:**
- Modify: `backend/pom.xml` (parent `<version>` line 10; remove the temporary Byte Buddy/Mockito props from Task 2 Step 4)
- Modify: `backend/wealthview-api/src/main/java/com/wealthview/api/security/SecurityConfig.java` (imports; `ignoringRequestMatchers` block lines ~69–75)
- Modify: config YAML as surfaced by the properties migrator — candidates: `backend/wealthview-app/src/main/resources/application*.yml`, `backend/wealthview-api/src/main/resources/application*.yml` (exact set discovered in Step 3)
- Possibly modify: any `spring-boot-starter-*` coordinates that Boot 4 renamed (discovered in Step 2)

**Interfaces:**
- Consumes: green JDK-25 base from Task 2.
- Produces: the app on Spring Boot 4.1.x with green `mvn verify`; Jackson still emitting Jackson-2-compatible JSON via `spring.jackson.use-jackson2-defaults=true`.

- [ ] **Step 1: Bump the parent and drop the temporary pins**

In `backend/pom.xml`, change the parent version (line 10) to the resolved Spring Boot 4.1.x:
```xml
        <version>4.1.x</version>
```
Remove the `<byte-buddy.version>` and `<mockito.version>` properties added in Task 2 Step 4 (the Boot 4.1 BOM now supplies JDK-25-ready versions).

- [ ] **Step 2: Surface compilation breakage (discovery)**

Run: `mvn -f backend/pom.xml clean compile -B 2>&1 | tee /tmp/boot4-compile.log`
Expected: FAIL. Read the log to enumerate every broken symbol. Known breakage from this codebase:
- `AntPathRequestMatcher` (removed in Spring Security 7) — fixed in Step 4.
- Any renamed `spring-boot-starter-*` artifacts from Boot 4's modularization — fix coordinates in `pom.xml` as flagged.
Fix non-security compile errors here (starter renames, moved classes) guided by the [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide). Do NOT introduce new Java 23–25 syntax.

- [ ] **Step 3: Add the config-property migrator and capture renames (discovery)**

Temporarily add to `backend/wealthview-app/pom.xml` `<dependencies>`:
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-properties-migrator</artifactId>
            <scope>runtime</scope>
        </dependency>
```
Then boot the app under each profile and read the WARN output listing renamed/removed keys:
Run: `mvn -f backend/pom.xml -pl wealthview-app spring-boot:run -Dspring-boot.run.profiles=dev 2>&1 | grep -i 'properties-migrator\|deprecated\|renamed'`
Repeat for `docker`, `it`, `prod` profiles (prod may need env vars — a config-parse pass is enough). Record every flagged key.

- [ ] **Step 4: Migrate Spring Security 7 request matchers**

In `SecurityConfig.java`, replace the removed `AntPathRequestMatcher`. Remove its import (line 23) and add:
```java
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
```
Replace the `ignoringRequestMatchers(...)` block (lines ~69–75) with:
```java
                        .ignoringRequestMatchers(
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.POST, "/api/v1/auth/login"),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.POST, "/api/v1/auth/register"),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.POST, "/api/v1/auth/refresh"),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher(HttpMethod.POST, "/api/v1/auth/mfa/challenge"),
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher("/api/v1/auth/token/**"),
                                bearerAuthorizationHeaderMatcher()))
```
Verify the exact `PathPatternRequestMatcher` factory/package against the Spring Security 7 reference bundled with the resolved Boot 4.1.x (the class moved packages across 6.x); adjust the import if the IDE/compiler flags it. `HttpMethod` and `RequestMatcher` imports are already present. `bearerAuthorizationHeaderMatcher()` returns `RequestMatcher` and is unchanged.

- [ ] **Step 5: Apply the config-property renames from Step 3**

Edit each flagged key in the relevant `application*.yml`. Keep `spring.jackson.use-jackson2-defaults` in mind for the next step — do not migrate Jackson keys to 3 semantics yet.

- [ ] **Step 6: Add the Jackson-2 compatibility bridge**

In `backend/wealthview-app/src/main/resources/application.yml` (the base profile), add:
```yaml
spring:
  jackson:
    use-jackson2-defaults: true
```
If any code path fails to resolve Jackson 2 classes at runtime under Boot 4, add the deprecated stop-gap module to `wealthview-app`/`wealthview-api` as needed:
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-jackson2</artifactId>
        </dependency>
```
(This keeps `com.fasterxml.jackson.*` working; the full Jackson 3 move is Task 4.)

- [ ] **Step 7: Verify Hibernate 7 JSONB mappings still bind**

The entities using `@JdbcTypeCode(SqlTypes.JSON)` (e.g. `params_json`, `spending_tiers`) rely on Hibernate's JSON binding. Confirm they still map under Hibernate ORM 7:
Run: `mvn -f backend/pom.xml -pl wealthview-persistence verify -B`
Expected: the `@DataJpaTest` Testcontainers tests pass — JSONB round-trips work. Investigate any `@DataJpaTest` failure immediately (do not skip).

- [ ] **Step 8: Remove the properties-migrator**

Once Step 5 clears all warnings, remove the `spring-boot-properties-migrator` dependency added in Step 3 (it is a diagnostic aid, not for keeps).

- [ ] **Step 9: Full verify, module-by-module then whole**

Run: `mvn -f backend/pom.xml clean verify -B`
Expected: BUILD SUCCESS across all modules (unit + `@DataJpaTest` + app Failsafe ITs + five gates). If failures cluster in one module, run that module alone (`-pl wealthview-<module>`) to iterate faster, then re-run the whole build.

- [ ] **Step 10: Smoke-test the running app on Boot 4.1**

Run: `./wv up` → log in (`demo@wealthview.local` / `demo123`) → confirm dashboard, a projection run, and an account view render → `./wv down`.
Expected: no serialization/auth regressions; JSON responses remain snake_case.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "build: upgrade to Spring Boot 4.1

Move to Spring Boot 4.1.x (Spring Framework 7, Jakarta EE 11, Hibernate
ORM 7, Spring Security 7) on JDK 25. Drop the temporary Byte Buddy/Mockito
pin (now BOM-managed). Replace the removed AntPathRequestMatcher with
PathPatternRequestMatcher in SecurityConfig. Migrate renamed config
properties (surfaced via spring-boot-properties-migrator, then removed).
Keep Jackson 2 wire behavior via spring.jackson.use-jackson2-defaults; the
Jackson 3 source migration is a separate phase. Full mvn verify incl.
Failsafe ITs green; JSON stays snake_case.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013zqLcvSyE7cr1g5Z5m8zio"
```

---

## Task 4 — Phase 3: Migrate Jackson 2 → 3

Move source from `com.fasterxml.jackson.{databind,core,...}` to `tools.jackson.*` across the ~31 files, then drop the compatibility bridge. Annotations stay on `com.fasterxml.jackson.annotation.*`. The snake_case JSON contract must not change.

**Files:**
- Modify: the ~31 files importing `com.fasterxml.jackson.*` (enumerated in Step 2)
- Modify: `backend/wealthview-app/src/main/resources/application.yml` (remove `use-jackson2-defaults`); `pom.xml` (remove `spring-boot-jackson2` if added)

**Interfaces:**
- Consumes: green Boot 4.1 base from Task 3.
- Produces: the app on Jackson 3 with identical snake_case wire JSON; the bridge removed.

- [ ] **Step 1: Confirm JSON-contract test coverage exists (safety net)**

Run: `grep -rln 'snake_case\|jsonPath\|@JsonNaming' backend/*/src/test --include=*.java | head`
Expected: controller/IT tests assert JSON field names. If a load-bearing response (e.g. `ScenarioResponse`, `AccountResponse`) has no snake_case assertion, add one characterization test first (follow existing `@WebMvcTest`/IT patterns) so drift is caught. Only add tests for genuinely-uncovered contracts — do not pad.

- [ ] **Step 2: Enumerate the files to migrate (discovery)**

Run: `grep -rln 'import com.fasterxml.jackson' backend --include=*.java | grep -v /target/ | tee /tmp/jackson-files.txt`
Run: `grep -rhn 'import com.fasterxml.jackson' backend --include=*.java | grep -v /target/ | sed 's/^[0-9]*://' | sort | uniq -c | sort -rn`
This lists every file and every distinct import to rewrite.

- [ ] **Step 3: Rewrite processing imports to `tools.jackson.*` (keep annotations)**

For each file in `/tmp/jackson-files.txt`, change processing imports and leave annotation imports alone. Mapping:
- `com.fasterxml.jackson.databind.ObjectMapper` → `tools.jackson.databind.ObjectMapper` (prefer `tools.jackson.databind.json.JsonMapper` where a JSON-only mapper is built)
- `com.fasterxml.jackson.databind.JsonNode` → `tools.jackson.databind.JsonNode`
- `com.fasterxml.jackson.databind.SerializationFeature` → `tools.jackson.databind.SerializationFeature`
- `com.fasterxml.jackson.databind.PropertyNamingStrategies` → `tools.jackson.databind.PropertyNamingStrategies`
- `com.fasterxml.jackson.databind.annotation.JsonNaming` → **unchanged** (`com.fasterxml.jackson.databind.annotation` is a databind-annotation; verify per the Jackson 3 mapping — if the compiler flags it, move to `tools.jackson.databind.annotation.JsonNaming`)
- `com.fasterxml.jackson.core.type.TypeReference` → `tools.jackson.core.type.TypeReference`
- `com.fasterxml.jackson.core.JsonProcessingException` → **removed usage**: Jackson 3 throws unchecked `tools.jackson.core.JacksonException` / `JsonProcessingException` subclasses. Delete now-invalid `throws JsonProcessingException` and `catch (JsonProcessingException …)` clauses the compiler flags; convert to unchecked handling.
- `com.fasterxml.jackson.annotation.*` (`JsonInclude`, `JsonIgnoreProperties`, `JsonUnwrapped`) → **unchanged** (annotations stay on the original group ID by design).

Reference: [Introducing Jackson 3 support in Spring](https://spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring/). Do the rewrite file-by-file; after each handful, recompile to keep the error set small:
Run: `mvn -f backend/pom.xml clean compile -B`

- [ ] **Step 4: Reconcile the custom ObjectMapper / JsonMapper configuration**

Wherever a mapper is hand-built (SNAKE_CASE strategy, `SerializationFeature` tweaks), adapt to Jackson 3's immutable builder API (`JsonMapper.builder()...build()`). Ensure the global `PropertyNamingStrategies.SNAKE_CASE` is still applied so field names remain snake_case.

- [ ] **Step 5: Remove the Jackson-2 bridge**

Delete `spring.jackson.use-jackson2-defaults: true` from `application.yml` and remove the `spring-boot-jackson2` dependency if it was added in Task 3 Step 6.

- [ ] **Step 6: Full verify — the JSON contract is the gate**

Run: `mvn -f backend/pom.xml clean verify -B`
Expected: BUILD SUCCESS; the snake_case controller/IT assertions (Step 1) pass, proving no wire-format drift. Investigate any serialization test failure immediately.

- [ ] **Step 7: Smoke-test JSON responses**

Run: `./wv up`, then hit a couple of endpoints and eyeball snake_case:
Run: `curl -s -u demo@wealthview.local:demo123 http://localhost:80/api/v1/accounts | head -c 400` (or via the logged-in UI network tab). Run `./wv down` after.
Expected: field names are snake_case, values unchanged.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: migrate Jackson 2 to Jackson 3

Move processing imports from com.fasterxml.jackson.* to tools.jackson.*
across the backend (annotations stay on com.fasterxml.jackson.annotation
by design). Adapt the custom SNAKE_CASE JsonMapper to Jackson 3's
immutable builder and drop now-invalid checked JsonProcessingException
handling (Jackson 3 exceptions are unchecked). Remove the
use-jackson2-defaults bridge and the spring-boot-jackson2 stop-gap. JSON
stays snake_case, verified by the controller/IT contract tests.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013zqLcvSyE7cr1g5Z5m8zio"
```

---

## Task 5 — Phase 4: API-level idiom sweep (module-by-module, many small commits)

Behavior-preserving modernization to **API-level** Java 22–25 features only. This is a repeatable loop per module, each iteration under the existing tests + five gates. **No new syntax** (the hard guardrail).

**Files:**
- Modify: source across modules, one module per commit (`wealthview-persistence`, then `-core`, `-projection`, `-import`, `-api`, `-app`)

**Interfaces:**
- Consumes: green Jackson-3 base from Task 4.
- Produces: modernized source; identical behavior; gates green after every commit.

**Allowed (API-level):** `Math.clamp(...)`, `SequencedCollection` / `List.reversed()` / `getFirst()` / `getLast()`, `Stream.gather(...)`, scoped values where they cleanly replace a `ThreadLocal`, and other Java 22–25 library additions.

**Forbidden (new syntax — breaks Checkstyle/PMD):** flexible constructor bodies (statements before `super(...)`), module-import declarations (`import module …`), compact source files / instance main methods, unnamed variables `_` only if Checkstyle rejects it (verify), and any construct the gates cannot parse.

Repeat Steps 1–5 for each module in dependency order:

- [ ] **Step 1: Find candidate call sites in the module (discovery)**

Run (example for `Math.clamp`): `grep -rnE 'Math\.(min|max)\(Math\.(min|max)' backend/wealthview-<module>/src/main --include=*.java`
Run (manual reverse-iteration / last-element): `grep -rnE 'get\(.*size\(\) ?- ?1\)|Collections.reverse' backend/wealthview-<module>/src/main --include=*.java`
Record concrete sites. Only change a site when the idiom is a clear readability win (per the spec — do not churn for its own sake).

- [ ] **Step 2: Apply one idiom class at a time**

Make the minimal edit at each site (e.g. `Math.min(hi, Math.max(lo, x))` → `Math.clamp(x, lo, hi)` — confirm argument order matches `Math.clamp(value, min, max)`). Keep edits mechanical and behavior-identical.

- [ ] **Step 3: Run the module's tests**

Run: `mvn -f backend/pom.xml -pl wealthview-<module> test -B`
Expected: PASS. Behavior is unchanged, so existing tests are the proof.

- [ ] **Step 4: Run the module's gates**

Run: `mvn -f backend/pom.xml -pl wealthview-<module> verify -DskipITs -B`
Expected: PMD/CPD/SpotBugs/Checkstyle/JaCoCo pass. If Checkstyle/PMD errors with a parse failure, you introduced forbidden syntax — revert that edit.

- [ ] **Step 5: Commit the module's sweep**

```bash
git add backend/wealthview-<module>
git commit -m "refactor(<module>): adopt API-level Java 25 idioms

Behavior-preserving: <list the idiom classes applied, e.g. Math.clamp,
List.getLast()>. No new syntax (Checkstyle/PMD parser guardrail). Existing
tests and all five gates green.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013zqLcvSyE7cr1g5Z5m8zio"
```

- [ ] **Step 6 (after all modules): Final whole-repo verify**

Run: `mvn -f backend/pom.xml clean verify -B`
Expected: BUILD SUCCESS across the whole backend with ITs on JDK 25 / Boot 4.1 / Jackson 3.

---

## Post-Migration

- [ ] Update `CLAUDE.md` (tech-stack line "Java 21 / Spring Boot 3.5" → "Java 25 / Spring Boot 4.1"), the parent-pom `<description>` (line 22), and any docs referencing JDK 21 / Boot 3.5. (Separate `docs:` commit.)
- [ ] Update the project memory (`MEMORY.md`) latest-stack note.
- [ ] Run `mvn verify` locally one final time before the maintainer tags a release (CI skips ITs).

---

## Self-Review (performed by the plan author)

**1. Spec coverage:**
- Decision 1 (Approach A: JDK first, Boot on top) → Tasks 2 then 3. ✓
- Decision 2 (staged Jackson: shim then dedicated phase) → Task 3 Step 6 (shim) + Task 4 (migration). ✓
- Decision 3 (API-level idioms only) → Task 5 Allowed/Forbidden lists + Step 4 gate. ✓
- Decision 4 (PIT strict on 25) → Task 2 Step 8 + Task 1 Step 8 sanity. ✓
- Target versions (Boot 4.1, Byte Buddy/Mockito/PMD/SpotBugs/Checkstyle floors, JaCoCo unchanged) → Global Constraints + Task 1/Task 2. ✓
- Phase 0 separate → Task 1. ✓
- Docker + CI pins → Task 2 Steps 5–6. ✓
- SecurityConfig `AntPathRequestMatcher` swap → Task 3 Step 4. ✓
- Jackson 3 across 31 files + annotations stay → Task 4 Step 3. ✓
- Per-phase local `mvn verify` gate + one commit + independent revert → every task. ✓
- Coverage floors hold → Global Constraints; gates enforce. ✓
- Mobile untouched → Global Constraints; Prerequisite `.sdkmanrc` note. ✓

**2. Placeholder scan:** Version tokens (`25.0.x-tem`, `4.1.x`, `<RESOLVE_DIGEST>`, `LATEST_PITEST`) are explicit "resolve-at-implementation-time" values, each with a command to resolve them and an instruction not to leave the token literal — these are external, time-varying facts, not design gaps. Discovery steps (config-property list, Jackson file list, idiom sites) produce their own concrete work-lists via given commands. No "add error handling"/"write tests for the above"/"TODO" placeholders.

**3. Type/name consistency:** Property names `byte-buddy.version`/`mockito.version` (the Boot-parent-managed keys) used consistently in Task 2 (add) and Task 3 (remove). `PathPatternRequestMatcher` used consistently. `pmd.core.version`/`spotbugs.core.version`/`checkstyle.core.version` defined in Task 1 and referenced in the same task. `spring.jackson.use-jackson2-defaults` added in Task 3, removed in Task 4. Consistent.
