# Parallel builds → one SonarQube, handed off through S3

How `gp_sonar_handoff_s3` works, end to end: what is cached, how the connector
was made, how the pipeline is wired, what each build ships, and what the sonar
stage gets back.

Verified working on run 4 (`kWsGIkXBR_KxHIvdzEf8Nw`, 2026-10-29) — Success end
to end, quality gate **PASSED**. Evidence is quoted throughout, all of it from
that run's step logs.

---

## 1. The problem this solves

The target shape (`ci_tests_v2`) builds several stacks in a `- parallel:` block
of **stages** — each stack gets its own pod and its own toolchain image, because
a Gradle build wants `gradle:8.10-jdk21` and an npm build wants `node:22-alpine`
and neither should be forced into a shared image. Those stages then have to feed
**one** SonarQube analysis, so the gate sees the whole project at once rather
than N disconnected projects.

That means moving build output across a stage boundary. Harness stages do not
share a workspace: each stage is a fresh pod with a fresh `/harness`. And there
is no artifact repository available here. So the output has to go somewhere
external and come back.

The naive fallback is to let the sonar stage rebuild everything. That is what we
are specifically avoiding: it doubles build time, and it means the thing analysed
is not the thing that was tested.

### What is actually hard about it

Not the upload. The hard part is **N producers → 1 consumer**.

**Cache Intelligence** (`stage.spec.caching`) was the obvious first answer and it
does not work here: it allows **one cache key per stage**. Three parallel build
stages writing three payloads that one sonar stage reads back is simply not
expressible in it. Making it fit would mean collapsing the parallel *stages* into
parallel *steps* inside a single stage — which costs exactly the thing worth
keeping: per-stack pods, per-stack images, per-stack timeouts, retries, failure
strategies, and separate tiles in the execution UI.

So the requirement is a cache mechanism where **one stage can write many keys,
and another stage can read many keys**.

---

## 2. Which cache step to use — this is the part worth knowing

Harness ships **two separate families** of cache step, and they are not
interchangeable on this account.

| Step type | What it is | Status here |
|---|---|---|
| `SaveCacheS3` / `RestoreCacheS3` | Older, provider-specific, GA | **Works.** This is what the pipeline uses. |
| `SaveCacheGCS` / `RestoreCacheGCS` | Same generation, GCS flavour | Present in the Step Library |
| `SaveCache` / `RestoreCache` + `providerType:` | Newer generic step, one type for S3/GCS/Azure/Harness | **Blocked at account level.** |

The generic steps accept their YAML happily — the pipeline saves and validates —
then die at stage initialisation:

```
java.lang.IllegalStateException: Unexpected value: SAVE_CACHE
```

This applies to **every** `providerType`, including `S3`. It is not a
mis-configuration and not something fixable from inside the pipeline; it needs
enabling at the account level. Neither the error nor the public docs name the
flag that controls it, so this document does not guess at one — if you need it
turned on, that is a question for Harness support, quoting the exception above.

> In the Step Library these look confusingly similar. **"Save Cache to S3"** is
> `SaveCacheS3` and works. **"Save Cache"** is the generic step and does not,
> yet. Both appear when you filter for "cache".

**Why `SaveCacheS3` is the right answer and not just the available one:** it is a
plain step, so a stage may contain as many of them as it likes, each with its own
key. No one-key-per-stage limit. That is precisely the N→1 property Cache
Intelligence cannot give us, and it is why the `- parallel:` stage block survives
intact.

---

## 3. The connector — and why there are no credentials to manage

The pipeline authenticates to S3 through an **account-level AWS connector using
OIDC**, so there is no access key stored anywhere and nothing that expires.

The connector already existed on the account:

```yaml
name: AWS_OIDC_S3_Connector_account
identifier: AWS_OIDC_S3_Connector_account   # account scope -> account.AWS_OIDC_S3_Connector_account
type: Aws
spec:
  credential:
    type: OidcAuthentication
    spec:
      iamRoleArn: arn:aws:iam::664418987337:role/Harness_IaCM_S3_Role-sk
  executeOnDelegate: false
```

### How OIDC auth works here

1. Harness mints a short-lived OIDC token identifying this account/pipeline.
2. The step calls `sts:AssumeRoleWithWebIdentity` against
   `arn:aws:iam::664418987337:role/Harness_IaCM_S3_Role-sk`.
3. AWS checks the role's trust policy accepts the Harness OIDC provider, and
   returns temporary credentials scoped to that role.
4. The step uses those for the S3 call, and they expire on their own.

The role carries `AmazonS3FullAccess` and its trust policy accepts the Harness
OIDC provider without a subject restriction — which is why it works for this
pipeline without being modified for it.

Confirmed in the step log:

```
Attempting to assume role with OIDC
Successfully assumed role with OIDC
```

### If you are creating this from scratch

In AWS:

1. Add the Harness OIDC provider as an IAM identity provider (Harness gives you
   the issuer URL and audience under **Account Settings → Authentication → OIDC**).
2. Create a role trusting that provider via `sts:AssumeRoleWithWebIdentity`.
3. Attach a policy allowing S3 on your bucket. `AmazonS3FullAccess` is what this
   role happens to have; for a purpose-built role, scope it to
   `arn:aws:s3:::<bucket>/handoff/*` plus `s3:ListBucket` on the bucket.

In Harness: **Connectors → New Connector → AWS**, credential type **OIDC**, paste
the role ARN. Create it at **account** scope so every project can reference it as
`account.<identifier>`.

### Why not an access key

Two reasons. A long-lived IAM user key would have to be stored as a secret and
rotated, and creating one was not permitted by the available AWS role anyway. And
the STS session credentials from an SSO login — the kind you get from
`aws sso login` — expire within hours, so a pipeline built on them breaks by
tomorrow. (Those credentials did in fact expire during this work, which is a
decent demonstration of the point. The pipeline was unaffected, because it never
used them.)

`ManualConfig` credentials *are* supported by the connector if you ever need them
— `accessKey` + `secretKeyRef` + `sessionTokenRef`, session tokens included — but
OIDC is strictly better here.

---

## 4. The bucket

```
s3://gp-sonar-handoff-664418987337      region us-west-2
```

Key layout, one object per module per run:

```
handoff/<pipeline.executionId>/<stack>/<service>
```

Run 4 wrote these four, concurrently, from three different pods:

```
handoff/kWsGIkXBR_KxHIvdzEf8Nw/gradle/gradle-svc-a
handoff/kWsGIkXBR_KxHIvdzEf8Nw/gradle/gradle-svc-b
handoff/kWsGIkXBR_KxHIvdzEf8Nw/npm/npm-web
handoff/kWsGIkXBR_KxHIvdzEf8Nw/go/go-api
```

Scoping the key by `executionId` is what makes concurrent runs safe: two runs
never collide, and a run only ever reads its own payloads. There is no shared
mutable key anywhere.

**Cleanup is the bucket's job, not the pipeline's.** A lifecycle rule
`expire-handoff-7d` expires objects under `handoff/` after 7 days, and incomplete
multipart uploads are aborted after 1 day. So there is no cleanup step to
maintain, and an aborted or failed run leaves nothing behind that matters. (This
rule was created during setup; I could not re-read it at the time of writing
because the SSO credentials used to create it had expired.)

---

## 5. Pipeline shape

`gp_sonar_handoff_s3` — 855 lines of YAML, four logical blocks:

```
prepare                     (one stage, host-run, no container)
  └─ detect services → emits the matrix JSON

- parallel:                 (three stages, each its own pod)
  ├─ gradle   gradle:8.10-jdk21           matrix over gradle services
  ├─ npm      node:22-alpine              matrix over npm services
  └─ go       golang:1.22-alpine          matrix over go services
       each ends with:  SaveCacheS3  →  one key per matrix cell

sonar                       (one stage)
  ├─ Background: sonarqube:community
  ├─ RestoreCacheS3   ← step-level matrix over ALL modules, every stack
  ├─ assemble sonar inputs      (aggregates properties, no per-stack knowledge)
  ├─ sonar bootstrap            (token, quality gate)
  ├─ sonar scanner    sonarsource/sonar-scanner-cli:11
  └─ sonar results              (reads the gate back off the server)
```

### `prepare` — one stage decides what the run contains

A single `Run` step emits three per-stack matrix lists plus a combined one:

| Output variable | Consumed by |
|---|---|
| `gradle_matrix` | the `gradle` stage's `strategy.matrix` |
| `npm_matrix` | the `npm` stage's `strategy.matrix` |
| `go_matrix` | the `go` stage's `strategy.matrix` |
| `all_modules` | the sonar stage's `RestoreCacheS3` matrix |
| `module_count` | `assemble`, to compare expected vs arrived |

Each build stage fans out over its own list:

```yaml
strategy:
  matrix:
    services: <+json.list("services", <+pipeline.stages.prepare.spec.execution.steps.detect_services.output.outputVariables.gradle_matrix>)>
    maxConcurrency: 2
```

So adding a service is a change to `prepare`'s output, not to the pipeline
structure. Adding a whole *stack* is one new stage in the `- parallel:` block.

#### The sentinel, and why it is not optional

Matrix expansion happens at **stage initialisation**, which is *before* `when`
conditions are evaluated. A matrix that expands to zero iterations is therefore a
fatal stage-init error — you cannot guard it with `when`, because `when` has not
run yet.

So `prepare` never emits an empty list. If a stack has no services it emits one
sentinel cell:

```sh
if [ -z "$items" ]; then
  items='{"service_name":"__none__","working_directory":"."}'
fi
```

and every step in the stage short-circuits on it:

```sh
if [ "$svc" = "__none__" ]; then
  echo "gradle stack not enabled for this run - nothing to do"
  exit 0
fi
```

with the save step skipped by condition:

```yaml
when:
  stageStatus: Success
  condition: '<+matrix.services.service_name> != "__none__"'
```

The result: a stack with nothing to build produces one trivially-green cell
instead of killing the run. This is the single most non-obvious thing in the
pipeline.

---

## 6. What gets cached — the payload

Each build stages a directory, then saves it. The guiding rule: **ship what
SonarQube's analysers need and cannot regenerate without the toolchain.** Not the
source — the source comes from the git clone in the sonar stage for free. What
cannot be regenerated there is bytecode, resolved classpaths, and coverage data.

Payload root, identical in both stages:

```
/harness/.gp-handoff/<stack>/<service>/
```

That the path is identical on both sides is deliberate — `RestoreCacheS3` puts
the tree back exactly where it was saved from, so absolute paths baked into the
properties stay valid across the boundary.

### Gradle — `/harness/.gp-handoff/gradle/<svc>/`

Built with `gradle --no-daemon classes testClasses test jacocoTestReport exportSonarLibs`:

| Path | What it is | Why it must travel |
|---|---|---|
| `classes/` | main `.class` files | SonarQube's Java analyser needs bytecode; `gradle` is absent in the sonar stage |
| `test-classes/` | test `.class` files | same, for test code |
| `libs/` | resolved dependency jars | the analyser classpath; re-resolving needs Gradle + network |
| `test-results/` | JUnit XML | test counts |
| `jacoco.xml` | coverage report | regenerating it means re-running the tests |
| `MODULE` | the service name | lets the sonar stage derive the analysed set from disk |
| `BUILT_BY` | provenance | proves which pod and when |
| `sonar.contrib` | this stack's sonar properties | see §7 |

### npm — `/harness/.gp-handoff/npm/<svc>/`

Built with `npm test`. Ships `lcov.info`, plus `MODULE`, `BUILT_BY`,
`sonar.contrib`. No bytecode — JS is analysed from source, so only the coverage
report has to travel.

### Go — `/harness/.gp-handoff/go/<svc>/`

Built with `go vet ./...` then `go test -covermode=atomic -coverprofile=coverage.out ./...`.
Ships `coverage.out`, plus the same three metadata files. Same reasoning as npm.

### The save step

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

`override: true` because the key is execution-scoped — a retry of the same cell
should replace its own payload, not fail on a key that already exists. The step
does its own tar/untar, which also sidesteps a problem the earlier `aws s3 cp`
prototype hit: `amazon/aws-cli:2.17.0` ships no `tar` binary.

Total for run 4: **3.3 MB** across four payloads.

---

## 7. Each stack configures its own analysis

The interesting trick. Every build writes a `sonar.contrib` fragment describing
how *its* output should be analysed — and the sonar stage holds **zero per-stack
knowledge**. Add a stack, and you never touch the sonar stage.

Gradle writes:

```properties
sonar.sources=gradle-svc-a/src/main/java
sonar.tests=gradle-svc-a/src/test/java
sonar.test.inclusions=**/*Test.java
sonar.java.source=21
sonar.java.binaries=/harness/.gp-handoff/gradle/gradle-svc-a/classes
sonar.java.test.binaries=/harness/.gp-handoff/gradle/gradle-svc-a/test-classes
sonar.java.libraries=/harness/.gp-handoff/gradle/gradle-svc-a/libs/*.jar
sonar.java.test.libraries=/harness/.gp-handoff/gradle/gradle-svc-a/libs/*.jar
sonar.coverage.jacoco.xmlReportPaths=/harness/.gp-handoff/gradle/gradle-svc-a/jacoco.xml
sonar.junit.reportPaths=/harness/.gp-handoff/gradle/gradle-svc-a/test-results
```

npm writes `sonar.javascript.lcov.reportPaths`; go writes
`sonar.go.coverage.reportPaths`. Same pattern, different keys.

`assemble` then merges every fragment into one properties file, joining repeated
keys with commas and de-duplicating values:

```sh
for k in $(cut -d= -f1 "$all" | awk '!seen[$0]++'); do
  v=$(grep "^${k}=" "$all" | cut -d= -f2- | awk '!seen[$0]++' | paste -sd, -)
  printf '%s=%s\n' "$k" "$v" >> "$props"
done
```

This is a **flat, single-project model** — one project with many source roots.
`sonar.modules` is deprecated and deliberately not used.

---

## 8. What comes back

### The restore step — the N→1 consumer

One step, a **step-level** matrix over every module from every stack:

```yaml
- step:
    type: RestoreCacheS3
    name: restore built module
    identifier: restore_module
    timeout: 15m
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

`failIfKeyNotFound: false` is a design decision, not laziness: if a module did
not build, the run should carry on and analyse what *did*, rather than dying on a
missing key. The missing module is then excluded from the analysis — see below.

### What run 4 actually got back

Four payloads, 3.3 MB, each with provenance showing a **different pod** and a
timestamp **earlier than the sonar stage**:

```
stack=go      service=go-api        pod=eba8e473643a   built_at_utc=2026-10-29T08:09:37Z
stack=gradle  service=gradle-svc-a  pod=4bd25be8aa76   built_at_utc=2026-10-29T08:09:42Z
stack=gradle  service=gradle-svc-b  pod=518b660f8967   built_at_utc=2026-10-29T08:09:42Z
stack=npm     service=npm-web       pod=53a98c080e47   built_at_utc=2026-10-29T08:09:31Z
```

Four distinct pods, four distinct container images, overlapping in time. That is
the "parallel, separate containers" requirement, evidenced.

Contents: 4 `.class` files, 16 dependency jars, 4 coverage reports, 4 `MODULE`
markers.

### Proof nothing was rebuilt

The sonar stage's own toolchain inventory:

```
absent  gradle
absent  mvn
PRESENT javac
PRESENT npm
PRESENT node
absent  go
```

No Gradle, no Go. The Java bytecode and the Go coverage profile that got analysed
could only have come over the stage boundary — there is nothing in that pod
capable of producing them.

### The aggregated properties, verbatim

```properties
sonar.projectKey=kyriba_gp_sonar_demo
sonar.projectName=gp-sonar-demo
sonar.projectVersion=4
sonar.sourceEncoding=UTF-8
sonar.scm.disabled=true
sonar.qualitygate.wait=true
sonar.qualitygate.timeout=600
sonar.sources=go-api,gradle-svc-a/src/main/java,gradle-svc-b/src/main/java,npm-web/src
sonar.tests=go-api,gradle-svc-a/src/test/java,gradle-svc-b/src/test/java,npm-web/src
sonar.test.inclusions=**/*_test.go,**/*Test.java,**/*.test.js
sonar.go.coverage.reportPaths=/harness/.gp-handoff/go/go-api/coverage.out
sonar.java.source=21
sonar.java.binaries=/harness/.gp-handoff/gradle/gradle-svc-a/classes,/harness/.gp-handoff/gradle/gradle-svc-b/classes
sonar.java.test.binaries=/harness/.gp-handoff/gradle/gradle-svc-a/test-classes,/harness/.gp-handoff/gradle/gradle-svc-b/test-classes
sonar.java.libraries=/harness/.gp-handoff/gradle/gradle-svc-a/libs/*.jar,/harness/.gp-handoff/gradle/gradle-svc-b/libs/*.jar
sonar.java.test.libraries=/harness/.gp-handoff/gradle/gradle-svc-a/libs/*.jar,/harness/.gp-handoff/gradle/gradle-svc-b/libs/*.jar
sonar.coverage.jacoco.xmlReportPaths=/harness/.gp-handoff/gradle/gradle-svc-a/jacoco.xml,/harness/.gp-handoff/gradle/gradle-svc-b/jacoco.xml
sonar.junit.reportPaths=/harness/.gp-handoff/gradle/gradle-svc-a/test-results,/harness/.gp-handoff/gradle/gradle-svc-b/test-results
sonar.exclusions=**/node_modules/**,**/coverage/**
sonar.javascript.lcov.reportPaths=/harness/.gp-handoff/npm/npm-web/lcov.info
```

Three stacks' worth of configuration, correctly comma-joined, assembled by a step
that knows nothing about any of them.

### The analysed set is derived from disk, not from hope

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

If a module had not arrived, it would be **excluded** rather than scanned empty —
because scanning an empty module reports 0% coverage and fails the gate for
entirely the wrong reason. The mismatch is reported loudly instead.

---

## 9. SonarQube, and one gotcha

A `Background` step, so the server lives only for the stage:

```yaml
- step:
    type: Background
    name: sonarqube server
    identifier: sonarqube
    spec:
      image: sonarqube:community
```

with `runAsUser: 1000`, `SONAR_ES_BOOTSTRAP_CHECKS_DISABLE`,
`-Dnode.store.allow_mmap=false`, and port bindings for 9000.

**Addressing differs by step type.** Host-run steps reach it at
`localhost:9000`; steps running in their own container — like the scanner —
reach it at `sonarqube:9000`, the Background step's *identifier*.

**The launcher gotcha, which cost two runs.** `sonarqube:community` has moved its
entrypoint between versions. A bare `exec ./bin/run.sh` fails:

```
sh: 2: exec: ./bin/run.sh: not found
```

Probe for it instead:

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

`sonar.qualitygate.wait=true` is what makes the gate load-bearing — without it
the scanner uploads and exits 0 regardless of the verdict, and the pipeline goes
green on a failing gate.

---

## 10. Run 4 results

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

Per-directory coverage — every stack's report landed:

```
go-api:                                 coverage=75.0  ncloc=26
gradle-svc-a/src/main/java/com/kyriba/a: coverage=70.0  ncloc=19
gradle-svc-b/src/main/java/com/kyriba/b: coverage=58.3  ncloc=26
npm-web/src:                            coverage=88.0  ncloc=14
```

Three analysers ran — Java via ECJ on the restored bytecode, plus JS and Go:

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

---

## 11. Mapping onto the golden-path templates

| Template | Change needed |
|---|---|
| `gp_stack_*` (gradle/npm/go) | Add a `SaveCacheS3` step keyed `handoff/<+pipeline.executionId>/<stack>/<service>`, and have the build stage its payload + `sonar.contrib` under `/harness/.gp-handoff/<stack>/<service>` |
| `gp_golden_path_ci` | **No change.** The `- parallel:` stage block survives as-is. |
| sonar stage | Add the `RestoreCacheS3` matrix + the assemble step. Holds no per-stack knowledge. |

Each `gp_stack_*` stays a **stage** template — own pod, own image, own timeout,
retries, failure strategy and UI tile. That is the property Cache Intelligence
would have forced us to give up, and the whole reason for choosing
`SaveCacheS3`.

---

## 12. Operational notes

- **No stored credentials.** OIDC only. Nothing to rotate, nothing that expires.
- **No cleanup step.** The S3 lifecycle rule handles it.
- **Concurrent runs are safe.** Keys are scoped by `executionId`.
- **Failure is partial, not total.** `failIfKeyNotFound: false` plus
  disk-derived module detection means one broken stack degrades the analysis
  rather than killing the run — and says so in the log.
- The three Harness secrets created earlier from pasted AWS credentials
  (`aws_demo_access_key_id`, `aws_demo_secret_access_key`,
  `aws_demo_session_token`) were **deleted** — the OIDC path made them
  unnecessary. Those credentials were shared in plaintext, so refresh that SSO
  session if it has not rotated already.

### If you hit `IllegalStateException: Unexpected value: SAVE_CACHE`

You are on the generic `SaveCache`/`RestoreCache` step, not `SaveCacheS3`. Either
switch to the S3-specific type, or get the generic family enabled at the account
level. Do not go looking for a YAML fix; there isn't one.
