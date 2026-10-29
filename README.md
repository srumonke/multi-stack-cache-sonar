# multi-stack-cache-sonar

**Parallel multi-stack builds in Harness CI, each in its own container, handing
off to a single SonarQube analysis through S3 caching — with nothing rebuilt.**

Four services across three toolchains build **concurrently in separate pods**.
Each ships only what SonarQube's analysers cannot regenerate — bytecode,
resolved classpaths, coverage reports — to S3. One sonar stage restores all of
it and runs **exactly one** analysis against a real SonarQube server, with no
build tool present to rebuild anything.

```
                    ┌──────────────────────────────────────────┐
                    │  prepare   (scans application/, emits    │
                    │             one matrix per stack)        │
                    └────────────────────┬─────────────────────┘
                                         │
         ┌───────────────────────────────┼───────────────────────────────┐
         │                               │                               │
  ┌──────▼──────┐               ┌────────▼────────┐             ┌───────▼───────┐
  │   gradle    │               │       npm       │             │      go       │
  │ own pod     │               │ own pod         │             │ own pod       │
  │ gradle:8.10 │               │ node:22-alpine  │             │ golang:1.22   │
  │   -jdk21    │               │                 │             │   -alpine     │
  │ x2 services │               │ x1 service      │             │ x1 service    │
  └──────┬──────┘               └────────┬────────┘             └───────┬───────┘
         │ SaveCacheS3                   │ SaveCacheS3                  │ SaveCacheS3
         └───────────────────────────────┼──────────────────────────────┘
                                         │
                       s3://.../handoff/<executionId>/<stack>/<service>
                                         │
                    ┌────────────────────▼─────────────────────┐
                    │  sonar     RestoreCacheS3 (matrix, N->1) │
                    │            then aggregate properties     │
                    │            then one scan, one project    │
                    │            SonarQube quality gate        │
                    │   no gradle - no go - nothing rebuilt    │
                    └──────────────────────────────────────────┘
```

**Status: verified working end to end.** Quality gate **PASSED**, 73.2%
coverage, all four stacks' coverage reports imported, four payloads from four
distinct pods. Evidence in [section 8](#8-verified-results).

---

## Contents

| Path | What it is |
| --- | --- |
| [`application/`](application) | The four demo services — the code that gets built and scanned |
| [`harness/gp_sonar_handoff_s3.yaml`](harness/gp_sonar_handoff_s3.yaml) | The working pipeline |
| [`docs/S3_HANDOFF_SETUP.md`](docs/S3_HANDOFF_SETUP.md) | Deep dive: connector creation, bucket, payload internals, gotchas |

1. [The problem](#1-the-problem)
2. [Why this is harder than it looks](#2-why-this-is-harder-than-it-looks)
3. [Which cache step to use](#3-which-cache-step-to-use--the-part-that-actually-matters)
4. [The application](#4-the-application)
5. [What gets cached](#5-what-gets-cached)
6. [How the pipeline is wired](#6-how-the-pipeline-is-wired)
7. [Setting it up yourself](#7-setting-it-up-yourself)
8. [Verified results](#8-verified-results)
9. [Troubleshooting](#9-troubleshooting)

---

## 1. The problem

You have a monorepo with several services on different toolchains. You want to:

- build and test them **in parallel**, each in **its own container**, because a
  Gradle build wants a JDK image and an npm build wants a Node image and neither
  should be forced to share;
- feed **one** SonarQube analysis, so the quality gate judges the whole project
  rather than N disconnected ones;
- **not rebuild anything** in the analysis stage.

That last point is the whole game. SonarQube's Java analyser needs real
**bytecode** and a real **classpath** to resolve types — without them it
silently degrades to a much weaker text-level analysis. Coverage needs the
report produced by the test run. All of those live in the build stage's
workspace.

And Harness stages do not share a workspace. **Each stage is a fresh pod with a
fresh `/harness`.** So the output has to leave the pod and come back.

The lazy fallback is to let the sonar stage rebuild everything. That doubles
build time, and — worse — means the thing you analysed is not the thing you
tested.

## 2. Why this is harder than it looks

The upload isn't the hard part. The hard part is **N producers to 1 consumer**.

**Cache Intelligence** (`stage.spec.caching`) is the obvious first answer, and it
does not work here: it allows **one cache key per stage**. Three parallel build
stages writing three payloads for one sonar stage to read back is simply not
expressible in it.

You *can* make Cache Intelligence fit — by collapsing the parallel **stages**
into parallel **steps** inside one stage. But that costs exactly what's worth
keeping:

| | Parallel stages | Parallel steps in one stage |
| --- | --- | --- |
| Own pod per stack | yes | no — one shared pod |
| Own toolchain image | yes | awkward; shared workspace |
| Own timeout / retries / failure strategy | yes | no |
| Separate tile in the execution UI | yes | no |
| Works with Cache Intelligence | **no** | yes |

So the real requirement is a cache mechanism where **one stage can write many
keys and another can read many keys**. That rules Cache Intelligence out and
points at explicit cache steps.

## 3. Which cache step to use — the part that actually matters

Harness ships **two separate families** of cache step. They look nearly
identical in the Step Library and they are **not** interchangeable.

| Step type | Library name | What it is | Status |
| --- | --- | --- | --- |
| `SaveCacheS3` / `RestoreCacheS3` | "Save Cache to S3" | Older, provider-specific, GA | **Works — use this** |
| `SaveCacheGCS` / `RestoreCacheGCS` | "Save Cache to GCS" | Same generation, GCS | Available |
| `SaveCache` / `RestoreCache` + `providerType:` | "Save Cache" | Newer generic step, one type for S3/GCS/Azure/Harness | **May be gated** |

The generic steps accept their YAML happily — the pipeline saves and validates
cleanly — then die at **stage initialisation**:

```
java.lang.IllegalStateException: Unexpected value: SAVE_CACHE
```

On the account this was built against, that happened for **every**
`providerType`, including `S3`. It is not a misconfiguration and there is no
YAML-level fix; it needs enabling at the account level. Neither the error nor
the public docs name the flag that controls it, so this README does not guess at
one — if you need it on, quote that exception to Harness support.

> **If you take one thing from this repo:** use `SaveCacheS3`, not `SaveCache`.
> Filtering the Step Library for "cache" shows both, a line apart.

**Why `SaveCacheS3` is the right answer and not merely the available one:** it is
an ordinary step, so a stage may contain as many as it likes, each with its own
key. No one-key-per-stage limit. That is precisely the N-to-1 property Cache
Intelligence lacks, and it is why the `- parallel:` stage block survives intact.

## 4. The application

Four deliberately tiny services in [`application/`](application), chosen to make
the handoff problem concrete rather than to do anything useful.

| Module | Toolchain | What SonarQube needs from the build | Coverage format |
| --- | --- | --- | --- |
| [`gradle-svc-a`](application/gradle-svc-a) | Gradle 8.10 / Java 21 | compiled classes **+ resolved dependency jars** | JaCoCo XML |
| [`gradle-svc-b`](application/gradle-svc-b) | Gradle 8.10 / Java 21 | compiled classes **+ resolved dependency jars** | JaCoCo XML |
| [`npm-web`](application/npm-web) | Node 22 built-in test runner | nothing — analysed from source | lcov |
| [`go-api`](application/go-api) | Go 1.22 | nothing — analysed from source | coverprofile |

Two Gradle services exist on purpose: they prove the **matrix** fan-out inside a
single stage, alongside the fan-out *across* stages.

Three design choices in the code are worth knowing, because the demo's output
depends on them.

**Every module leaves exactly one function untested**, so reported coverage is a
real number rather than a trivially perfect one — `Describe` in
[`total.go`](application/go-api/total.go), `parseOrZero` in
[`Calc.java`](application/gradle-svc-a/src/main/java/com/kyriba/a/Calc.java),
`describe` in
[`Ledger.java`](application/gradle-svc-b/src/main/java/com/kyriba/b/Ledger.java),
`isEmpty` in [`total.js`](application/npm-web/src/total.js).

**Each untested function also carries one intentional code smell**, so the gate
has something real to say:

| Module | Rule | The smell |
| --- | --- | --- |
| `gradle-svc-a` | `java:S108` | empty `catch` block |
| `gradle-svc-b` | `java:S2583` | condition that can never be true (`balance > 0 && balance < 0`) |
| `npm-web` | `javascript:S1764` | identical sub-expressions either side of `\|\|` |

`gradle-svc-b`'s `java:S2583` is the one that earns its keep: it is a
**bytecode-aware** finding. Run the analysis without the compiled classes and
the classpath and SonarQube reports it with far less confidence, or not at all.
It is the canary for whether the handoff actually worked.

**No network dependencies in the npm and Go modules.** `npm-web` uses Node's
built-in test runner and `go-api` has no `require` block, so neither needs
registry access. Only the Gradle modules resolve dependencies (JUnit 5) — which
is exactly why their resolved jars are worth caching.

### The Gradle task that makes this possible

Each Gradle build declares one extra task. This is what turns an
un-reproducible classpath into a shippable directory:

```groovy
// Exports the resolved dependency jars into the build directory so a later
// stage can hand SonarQube's Java analyzer a real classpath without resolving
// dependencies, or touching the network, a second time.
tasks.register('exportSonarLibs', Copy) {
    from configurations.testRuntimeClasspath
    into layout.buildDirectory.dir('sonar-libs')
}
```

Without it, `sonar.java.libraries` in the sonar stage would have to point at a
Gradle cache that does not exist in that pod.

## 5. What gets cached

The rule: **ship what the analysers need and cannot regenerate without the
toolchain.** Not the source — the sonar stage's own git clone provides that for
free. What it cannot produce is bytecode, resolved classpaths and coverage data.

Payload path, identical on both sides of the boundary:

```
/harness/.gp-handoff/<stack>/<service>/
```

Identical by design: `RestoreCacheS3` puts the tree back exactly where it was
saved from, so absolute paths baked into the properties stay valid across the
stage boundary.

### Gradle payload

Built with `gradle --no-daemon classes testClasses test jacocoTestReport exportSonarLibs`:

| File / dir | Why it must travel |
| --- | --- |
| `classes/` | main bytecode — the Java analyser needs it; `gradle` is absent downstream |
| `test-classes/` | test bytecode |
| `libs/` | resolved dependency jars — re-resolving needs Gradle **and** network |
| `test-results/` | JUnit XML, for test counts |
| `jacoco.xml` | coverage — regenerating it means re-running the tests |
| `MODULE` | the service name; lets the sonar stage derive the analysed set from disk |
| `BUILT_BY` | provenance — pod hostname, UTC timestamp, build command |
| `sonar.contrib` | this stack's own sonar properties (see below) |

### npm and Go payloads

`npm-web` ships `lcov.info`; `go-api` ships `coverage.out`. Both also ship
`MODULE`, `BUILT_BY` and `sonar.contrib`. No bytecode — JS and Go are analysed
from source, so only the coverage report has to cross.

Run 4 total: **3.3 MB** across four payloads. Small, because only the
irreplaceable parts travel.

### Each stack configures its own analysis

The part worth stealing. Every build writes a `sonar.contrib` fragment
describing how *its* output should be analysed, and the sonar stage holds **zero
per-stack knowledge**. Adding a stack never means editing the sonar stage.

Gradle writes:

```properties
sonar.sources=application/gradle-svc-a/src/main/java
sonar.tests=application/gradle-svc-a/src/test/java
sonar.test.inclusions=**/*Test.java
sonar.java.source=21
sonar.java.binaries=/harness/.gp-handoff/gradle/gradle-svc-a/classes
sonar.java.test.binaries=/harness/.gp-handoff/gradle/gradle-svc-a/test-classes
sonar.java.libraries=/harness/.gp-handoff/gradle/gradle-svc-a/libs/*.jar
sonar.java.test.libraries=/harness/.gp-handoff/gradle/gradle-svc-a/libs/*.jar
sonar.coverage.jacoco.xmlReportPaths=/harness/.gp-handoff/gradle/gradle-svc-a/jacoco.xml
sonar.junit.reportPaths=/harness/.gp-handoff/gradle/gradle-svc-a/test-results
```

npm writes `sonar.javascript.lcov.reportPaths`; Go writes
`sonar.go.coverage.reportPaths`. Same pattern, different keys.

The `assemble` step then merges every fragment into one properties file, joining
repeated keys with commas and de-duplicating:

```sh
for k in $(cut -d= -f1 "$all" | awk '!seen[$0]++'); do
  v=$(grep "^${k}=" "$all" | cut -d= -f2- | awk '!seen[$0]++' | paste -sd, -)
  printf '%s=%s\n' "$k" "$v" >> "$props"
done
```

This is a **flat single-project model** — one project, many source roots.
`sonar.modules` is deprecated and deliberately unused.

## 6. How the pipeline is wired

[`harness/gp_sonar_handoff_s3.yaml`](harness/gp_sonar_handoff_s3.yaml), four
logical blocks.

### `prepare` — one stage decides what the run contains

A single `Run` step scans `application/` and emits the matrices. **Nothing
downstream hardcodes a service or stack list.**

| Output variable | Consumed by |
| --- | --- |
| `gradle_matrix` | the `gradle` stage's `strategy.matrix` |
| `npm_matrix` | the `npm` stage's `strategy.matrix` |
| `go_matrix` | the `go` stage's `strategy.matrix` |
| `all_modules` | the sonar stage's `RestoreCacheS3` matrix |
| `module_count` | `assemble`, to compare expected against arrived |

Detection is by marker file — `build.gradle` means gradle, `package.json` means
npm, `go.mod` means go. Each build stage then fans out over its own list:

```yaml
strategy:
  matrix:
    services: <+json.list("services", <+pipeline.stages.prepare.spec.execution.steps.detect_services.output.outputVariables.gradle_matrix>)>
    maxConcurrency: 2
```

**Adding a service** = drop a directory in `application/`. No pipeline change.
**Adding a stack** = one new stage in the `- parallel:` block.

#### The sentinel cell, and why it is not optional

Matrix expansion happens at **stage initialisation**, which is *before* `when`
conditions are evaluated. A matrix that expands to zero iterations is therefore
a fatal stage-init error — **and you cannot guard it with `when`, because `when`
has not run yet.**

So `prepare` never emits an empty list. If a stack has no services it emits one
sentinel:

```sh
if [ -z "$items" ]; then
  items='{"service_name":"__none__","working_directory":"."}'
fi
```

every step in the stage short-circuits on it:

```sh
if [ "$svc" = "__none__" ]; then
  echo "gradle stack not enabled for this run - nothing to do"
  exit 0
fi
```

and the save step is skipped by condition:

```yaml
when:
  stageStatus: Success
  condition: '<+matrix.services.service_name> != "__none__"'
```

A stack with nothing to build produces one trivially-green cell instead of
killing the run. **This is the least obvious thing in the pipeline** — try
`GP_STACKS_ENABLED=gradle,go` to watch npm take the sentinel path.

### The build stages — parallel, each its own pod

```yaml
- parallel:
    - stage: { name: gradle, ... }   # gradle:8.10-jdk21
    - stage: { name: npm,    ... }   # node:22-alpine
    - stage: { name: go,     ... }   # golang:1.22-alpine
```

Each ends with a save step, one key per matrix cell:

```yaml
- step:
    type: SaveCacheS3
    name: save payload to s3
    identifier: save_gradle_payload
    timeout: 15m
    spec:
      connectorRef: account.AWS_OIDC_S3_Connector_account
      region: us-west-2
      bucket: gp-sonar-handoff-664418987337
      key: handoff/<+pipeline.executionId>/gradle/<+matrix.services.service_name>
      sourcePaths:
        - /harness/.gp-handoff/gradle/<+matrix.services.service_name>
      archiveFormat: Tar
      override: true
    when:
      stageStatus: Success
      condition: '<+matrix.services.service_name> != "__none__"'
```

`override: true` because the key is execution-scoped: a retried cell should
replace its own payload, not fail on a key that already exists.

Scoping the key by `<+pipeline.executionId>` is what makes **concurrent runs
safe** — two runs never collide, and a run only ever reads its own payloads.
There is no shared mutable key anywhere.

### `sonar` — the N-to-1 consumer

One restore step with a **step-level** matrix over every module from every stack:

```yaml
- step:
    type: RestoreCacheS3
    name: restore built module
    identifier: restore_module
    spec:
      connectorRef: account.AWS_OIDC_S3_Connector_account
      region: us-west-2
      bucket: gp-sonar-handoff-664418987337
      key: handoff/<+pipeline.executionId>/<+matrix.modules.stack>/<+matrix.modules.service_name>
      archiveFormat: Tar
      failIfKeyNotFound: false
    strategy:
      matrix:
        modules: <+json.list("modules", <+pipeline.stages.prepare.spec.execution.steps.detect_services.output.outputVariables.all_modules>)>
        maxConcurrency: 1
```

`failIfKeyNotFound: false` is a design decision, not laziness: if one module
failed to build, the run should analyse what *did* arrive rather than dying on a
missing key.

Then the analysed set is derived **from disk**, not from hope:

```sh
for d in "$share"/*/*; do
  [ -f "$d/MODULE" ] || continue
  mods="${mods}${mods:+,}$(cat "$d/MODULE")"
done
```

```
detected by prepare : 4
arrived and scanned : 4  [go-api,gradle-svc-a,gradle-svc-b,npm-web]
```

A module that was expected but did not arrive is **excluded** rather than
scanned empty — because scanning an empty module reports 0% coverage and fails
the gate for entirely the wrong reason. The mismatch is reported loudly instead.

### SonarQube itself

A `Background` step, so the server lives only as long as the stage:

```yaml
- step:
    type: Background
    name: sonarqube server
    identifier: sonarqube
    spec:
      image: sonarqube:community
```

with `runAsUser: 1000`, `SONAR_ES_BOOTSTRAP_CHECKS_DISABLE`,
`-Dnode.store.allow_mmap=false` and port bindings for 9000.

Two things that will bite you, both covered in
[section 9](#9-troubleshooting): **how to address it** differs by step type, and
**the entrypoint has moved** between image versions.

## 7. Setting it up yourself

### Prerequisites

- A Harness account with CI enabled (Harness Cloud build infra — no delegate
  needed).
- An AWS account you can create an S3 bucket and an IAM role in.

### Step 1 — the S3 bucket

```bash
aws s3 mb s3://your-handoff-bucket --region us-west-2
```

Add a lifecycle rule so **cleanup is the bucket's job, not the pipeline's**.
This is why there is no cleanup step to maintain, and why a failed or aborted
run leaves nothing behind that matters:

```json
{
  "Rules": [
    {
      "ID": "expire-handoff-7d",
      "Status": "Enabled",
      "Filter": { "Prefix": "handoff/" },
      "Expiration": { "Days": 7 },
      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 1 }
    }
  ]
}
```

```bash
aws s3api put-bucket-lifecycle-configuration \
  --bucket your-handoff-bucket --region us-west-2 \
  --lifecycle-configuration file://lifecycle.json
```

### Step 2 — the IAM role, via OIDC

Use **OIDC**, not an access key. There is then no stored credential and nothing
that expires.

1. In Harness, go to **Account Settings → Authentication → OIDC** and note the
   issuer URL and audience.
2. In AWS IAM, add that issuer as an **identity provider**.
3. Create a role trusting it via `sts:AssumeRoleWithWebIdentity`.
4. Attach a policy. Scope it tightly:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject"],
      "Resource": "arn:aws:s3:::your-handoff-bucket/handoff/*"
    },
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::your-handoff-bucket"
    }
  ]
}
```

#### Why not an access key

A long-lived IAM user key must be stored as a secret and rotated. And STS
session credentials from an SSO login (`aws sso login`) expire within hours, so
a pipeline built on them breaks by tomorrow. The connector *does* support
`ManualConfig` — `accessKey` + `secretKeyRef` + `sessionTokenRef`, session
tokens included — if you have no choice. OIDC is strictly better.

### Step 3 — the Harness connector

**Connectors → New Connector → AWS**, credential type **OIDC**, paste the role
ARN. Create it at **account** scope so any project can reference it as
`account.<identifier>`.

```yaml
name: AWS_OIDC_S3_Connector_account
identifier: AWS_OIDC_S3_Connector_account
type: Aws
spec:
  credential:
    type: OidcAuthentication
    spec:
      iamRoleArn: arn:aws:iam::<account-id>:role/<your-role>
  executeOnDelegate: false
```

At runtime the step mints a short-lived OIDC token, calls
`AssumeRoleWithWebIdentity`, and uses the temporary credentials for the S3 call.
Confirm it in the step log:

```
Attempting to assume role with OIDC
Successfully assumed role with OIDC
```

### Step 4 — the pipeline

Import [`harness/gp_sonar_handoff_s3.yaml`](harness/gp_sonar_handoff_s3.yaml)
and change three things:

| What | Where |
| --- | --- |
| `connectorRef` | 4 occurrences — 3 save steps, 1 restore step |
| `bucket` | same 4 occurrences |
| `properties.ci.codebase` | `connectorRef` + `repoName` for **your** clone of this repo |

Then run it. Pipeline variables:

| Variable | Default | What it does |
| --- | --- | --- |
| `GP_APP_ROOT` | `application` | Where the services live. **The only place the repo layout is declared** — `prepare` emits `working_directory` relative to it and every downstream path derives from that. Use `.` for services at the repo root. |
| `GP_STACKS_ENABLED` | `auto` | `auto`, or a comma list like `gradle,go`. Set it to a subset to watch an absent stack take the sentinel path and be excluded from the analysis rather than scanned empty. |
| `GP_SONAR_PROJECT_KEY` | `kyriba_gp_sonar_demo` | SonarQube project key |
| `GP_ENFORCEMENT` | `warn` | `off` / `warn` / `fail`. `warn` reports the real gate result while keeping the build green. |

## 8. Verified results

Two runs are quoted below, both Success end to end:

- **run 4** (`kWsGIkXBR_KxHIvdzEf8Nw`) — the mechanism itself, with the services
  at the repo root.
- **the layout run** (`AS0FlxZ-S42-6MHR9Vt4lQ`) — the same pipeline with
  `GP_APP_ROOT=application`, confirming the `application/` layout in this repo
  changes nothing but the source paths.

Both produced **identical** measures — 73.2% coverage, 85 ncloc, 7 tests, 1 bug,
quality gate PASSED — so the layout is verifiably not load-bearing.

### Four pods, four images, overlapping in time

`BUILT_BY` provenance, written by each build and read back in the sonar stage:

```
stack=go      service=go-api        pod=eba8e473643a   built_at_utc=2026-10-29T08:09:37Z
stack=gradle  service=gradle-svc-a  pod=4bd25be8aa76   built_at_utc=2026-10-29T08:09:42Z
stack=gradle  service=gradle-svc-b  pod=518b660f8967   built_at_utc=2026-10-29T08:09:42Z
stack=npm     service=npm-web       pod=53a98c080e47   built_at_utc=2026-10-29T08:09:31Z
```

Four distinct hostnames, four distinct container images, timestamps overlapping
within 11 seconds. That is "parallel, separate containers", evidenced rather
than asserted.

### Nothing was rebuilt

The sonar stage's own toolchain inventory:

```
absent  gradle
absent  mvn
PRESENT javac
PRESENT npm
PRESENT node
absent  go
```

No Gradle. No Go. The Java bytecode and the Go coverage profile that got
analysed **could only have come over the stage boundary** — there is nothing in
that pod capable of producing them.

### Three stacks' properties, correctly merged

```properties
sonar.sources=go-api,gradle-svc-a/src/main/java,gradle-svc-b/src/main/java,npm-web/src
sonar.tests=go-api,gradle-svc-a/src/test/java,gradle-svc-b/src/test/java,npm-web/src
sonar.test.inclusions=**/*_test.go,**/*Test.java,**/*.test.js
sonar.java.binaries=/harness/.gp-handoff/gradle/gradle-svc-a/classes,/harness/.gp-handoff/gradle/gradle-svc-b/classes
sonar.coverage.jacoco.xmlReportPaths=/harness/.gp-handoff/gradle/gradle-svc-a/jacoco.xml,/harness/.gp-handoff/gradle/gradle-svc-b/jacoco.xml
sonar.javascript.lcov.reportPaths=/harness/.gp-handoff/npm/npm-web/lcov.info
sonar.go.coverage.reportPaths=/harness/.gp-handoff/go/go-api/coverage.out
```

Comma-joined and de-duplicated by a step that knows nothing about any of the
three stacks.

The source paths above are from run 4, which ran with the services at the repo
root. With `GP_APP_ROOT=application` — the layout in this repo — the same step
produced:

```properties
sonar.sources=application/go-api,application/gradle-svc-a/src/main/java,application/gradle-svc-b/src/main/java,application/npm-web/src
sonar.tests=application/go-api,application/gradle-svc-a/src/test/java,application/gradle-svc-b/src/test/java,application/npm-web/src
```

Note that the **payload** paths (`/harness/.gp-handoff/...`) are unchanged by
`GP_APP_ROOT`, and correctly so: those are staging locations inside the pod, not
repo paths. Only `sonar.sources` and `sonar.tests` are repo-relative.

### The gate

```
QUALITY GATE STATUS: PASSED
EXECUTION SUCCESS
```

Read back off the server afterwards:

```
overall: OK

bugs=1                        coverage=73.2
code_smells=0                 lines_to_cover=54
vulnerabilities=0             uncovered_lines=13
duplicated_lines_density=0.0  ncloc=85
tests=7
```

Per-directory coverage — **every stack's report landed**, which is the real
proof the handoff worked:

```
go-api:                                  coverage=75.0  ncloc=26
gradle-svc-a/src/main/java/com/kyriba/a: coverage=70.0  ncloc=19
gradle-svc-b/src/main/java/com/kyriba/b: coverage=58.3  ncloc=26
npm-web/src:                             coverage=88.0  ncloc=14
```

And from the layout run, the same numbers under the `application/` tree:

```
application:                   coverage=73.2  ncloc=85  uncovered_lines=13
application/go-api:            coverage=75.0  ncloc=26  uncovered_lines=3
application/gradle-svc-a/src:  coverage=70.0  ncloc=19  uncovered_lines=3
```

Three analysers ran, Java via ECJ against the **restored bytecode**:

```
Quality profile for go: Sonar way core
Quality profile for java: Sonar way core
Quality profile for js: Sonar way core
Sensor JavaSensor [java]
Using ECJ batch to parse 2 Main java source files with batch size 407 KB.
Sensor JavaSensor [java] (done) | time=590ms
```

And it found a real issue in the restored code:

```
[MAJOR] javascript:S1764  npm-web/src/total.js:17
Correct one of the identical sub-expressions on both sides of operator "||"
```

## 9. Troubleshooting

### `IllegalStateException: Unexpected value: SAVE_CACHE`

You are on the generic `SaveCache`/`RestoreCache` step, not `SaveCacheS3`.
Switch step type, or get the generic family enabled at account level. There is
no YAML fix — see
[section 3](#3-which-cache-step-to-use--the-part-that-actually-matters).

### `sh: exec: ./bin/run.sh: not found`

`sonarqube:community` has moved its entrypoint between versions. Probe for it
instead of hardcoding — this one cost two full runs:

```sh
cd "${SONARQUBE_HOME:-/opt/sonarqube}"
if [ -x ./docker/entrypoint.sh ]; then
  exec ./docker/entrypoint.sh
elif [ -x ./bin/run.sh ]; then
  exec ./bin/run.sh
else
  exec java -jar lib/sonarqube.jar
fi
```

### The scanner cannot reach SonarQube

Addressing depends on the step type. **Host-run** steps reach the background
service at `localhost:9000`. Steps running in **their own container** — like
`sonarsource/sonar-scanner-cli` — reach it at `sonarqube:9000`, using the
Background step's **identifier** as the hostname.

### The pipeline goes green on a failing gate

You are missing `sonar.qualitygate.wait=true`. Without it the scanner uploads
and exits 0 regardless of the verdict. That one property is what makes the gate
load-bearing.

### Stage init fails before any step runs

A matrix expanded to zero iterations. Expansion happens before `when` is
evaluated, so you cannot guard it with a condition — emit a sentinel cell
instead. See
[the sentinel cell](#the-sentinel-cell-and-why-it-is-not-optional).

### `tar: command not found` in a save step

Only relevant if you replace the cache steps with a hand-rolled `aws s3 cp`:
`amazon/aws-cli:2.17.0` ships no `tar`. `SaveCacheS3` does its own archiving, so
using it sidesteps the problem entirely.

### Java analysis is weaker than expected

Check that `sonar.java.binaries` and `sonar.java.libraries` actually resolve in
the sonar stage. If the bytecode or jars did not arrive, SonarQube degrades
silently rather than failing — which is exactly why `gradle-svc-b` carries a
bytecode-dependent smell (`java:S2583`) as a canary.

---

## Further reading

[`docs/S3_HANDOFF_SETUP.md`](docs/S3_HANDOFF_SETUP.md) — the deep dive:
connector internals, the OIDC assume-role flow, full payload tables, the
properties aggregation, and the complete run-4 evidence.
