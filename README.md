# JMeter + JUnit Load Test (standalone)

A self-contained reproduction of the **"JMeter drives the load, Java/JUnit code does the work"**
pattern — the same architecture as the internal `global-resource-routing-service-load-test`, but
with **zero internal/customer dependencies** so it builds and runs entirely on your machine.

JMeter's `JUnitSampler` invokes `@Test` methods on a plain Java class. Those methods fire
**async HTTP requests** and record **metrics**. By default the target is a tiny in-JVM mock
server, so the whole thing works offline; point it at any real service when you want.

```
JMeter (.jmx)  ──JUnitSampler──►  LoadTest @Test methods  ──►  async HTTP client  ──►  target
   threads / throughput / ramp        (fire-and-forget)          (CompletableFuture)     service
                                          │
                                          └──►  metrics (timers + meters)  ──►  console report
```

## Quick start

Two ready-made artifacts ship from this repo — use either, no build required.

### A. Docker image (`uditgaurav/sample-jmeter-test`)

```bash
docker pull uditgaurav/sample-jmeter-test:latest

# smoke test against the built-in mock server (fully offline); results land in ./out
docker run --rm -v "$PWD/out:/results" uditgaurav/sample-jmeter-test:latest -JDURATION_SECONDS=30

# load test a real service
docker run --rm -v "$PWD/out:/results" uditgaurav/sample-jmeter-test:latest \
  -JSTART_EMBEDDED_SERVER=false \
  -JTARGET_BASE_URL=https://your-service.example.com \
  -JTHREAD_NUMBER=50 -JDURATION_SECONDS=300 -JSCENARIO=Read
```

Any `-J<NAME>=<value>` after the image name overrides a parameter (see the table below). The
HTML report is written to `out/report/index.html`.

### B. Zip bundle (`sample-jmeter-test-bundle.zip`)

Download the zip from the repo, unzip, and run. Needs Java + JMeter — or `run.sh` will fetch
JMeter for you:

```bash
unzip sample-jmeter-test-bundle.zip
cd sample-jmeter-test-1.0.0
./run.sh -JDURATION_SECONDS=30
./run.sh -JSTART_EMBEDDED_SERVER=false -JTARGET_BASE_URL=https://your-service.example.com
```

The bundle contains the plan (`load-test.jmx`), `load.properties`, the shaded test jar
(`lib/junit/fat-jmeter-junit-load-test.jar`), and the `run.sh` runner.

## How it maps to the original project

| Original (`...-load-test`)            | Here                          | Role |
|---------------------------------------|-------------------------------|------|
| `global-resource-routing-service-load.jmx` | `src/main/jmeter/load-test.jmx` | JMeter plan: thread groups, throughput split, JUnitSamplers |
| `BasicTest`                           | `BasicTest`                   | Target URL, thread pools, delayed executor, JMeter-variable access, metrics |
| `GRRLoadTest` (`@Test` methods)       | `LoadTest` (`@Test` methods)  | The class JMeter calls; fire-and-forget async scenarios + `main()` |
| `GRRClientRequests`                   | `ClientRequests`              | Wraps the async HTTP client; one instrumented `CompletableFuture` per endpoint |
| `GrrMetrics` (Twilio meters → DataDog)| `LoadMetrics` (Dropwizard → console) | Request/test/data counters + latency timers |
| `PhoneNumberTracker` / `AccountTracker` | `ItemIdTracker` / `ResourceTracker` | Deterministic ids; loop + "unique-unused" (for 404s) |
| GRR service on `localhost:18150`      | `MockServer` on `localhost:18150` | The system under test |
| `load_config.yaml` (TAF params)       | `load.properties` + `run-jmeter.sh` | Parameters + headless runner |

The defining trick is identical: the `@Test` methods **submit** an async request chain to a thread
pool and return immediately, so JMeter measures only the trigger while the real per-request latency
is captured by the metric timers (the original sent these to DataDog; we print them to the console).

## Project layout

```
pom.xml                         # public deps only; builds a fat jar for lib/junit
load.properties                 # all tunables
run-jmeter.sh                   # build + install jar into JMeter + run headless
Dockerfile                      # 2-stage build: Maven -> JMeter runtime image
docker/entrypoint.sh            # container entrypoint: run plan headless -> /results
.dockerignore
sample-jmeter-test-bundle.zip   # the runnable zip artifact (plan + props + jar + runner)
scripts/
  build-bundle.sh               # build the fat jar and assemble the zip bundle
  publish.sh                    # docker build + push to Docker Hub
  bundle/run.sh                 # runner shipped inside the zip
  bundle/README.txt             # instructions shipped inside the zip
src/main/jmeter/load-test.jmx   # the JMeter test plan
src/main/java/com/example/load/
  BasicTest.java                # shared infra + param/JMeter glue
  LoadTest.java                 # @Test methods (init, setUpData, crudScenario, readScenario, teardown) + main()
  client/ClientRequests.java    # async HTTP + per-endpoint metrics/timers
  metrics/LoadMetrics.java      # Dropwizard meters/timers
  mock/MockServer.java          # in-JVM CRUD target (/items)
  util/ResourceTracker.java     # id generation base
  util/ItemIdTracker.java       # numeric ids
```

## Prerequisites

Installed via Homebrew on this machine: **OpenJDK 21**, **Maven**, **JMeter 5.6.3**.

OpenJDK is keg-only (not on `PATH`). For `java`/`mvn` commands, point `JAVA_HOME` at it:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

`run-jmeter.sh` auto-detects this, so you don't strictly need it for the JMeter path.

## Running it

### Option A — the full JMeter path (recommended)

Builds the fat jar, drops it into JMeter's `lib/junit/`, and runs headless:

```bash
./run-jmeter.sh
# override any property on the CLI:
./run-jmeter.sh -JDURATION_SECONDS=60 -JTHREAD_NUMBER=50 -JSCENARIO=Read
```

Outputs:
- live `summary = ... Err: 0 (0.00%)` lines + a console metrics dump
- `target/results.jtl` (raw samples)
- `target/report/index.html` (HTML dashboard)
- `target/jmeter.log`

Open the plan in the JMeter GUI instead (to edit visually):

```bash
jmeter -t src/main/jmeter/load-test.jmx -q load.properties
```

### Option B — run the Java load logic directly (no JMeter)

Fastest way to validate the code path. `main()` starts the mock server, drives load with a thread
pool, then prints the final metrics:

```bash
# via Maven (uses Maven's JDK automatically)
mvn -q compile exec:java -DSTANDALONE_RUN_SECONDS=15 -DSCENARIO=All

# or via the fat jar
java -DSTANDALONE_RUN_SECONDS=15 -DTHREAD_NUMBER=10 -DSCENARIO=All \
  -jar target/fat-jmeter-junit-load-test.jar
```

## Parameters (`load.properties`, override with `-J` for JMeter or `-D` for the jar)

| Name | Default | Meaning |
|------|---------|---------|
| `START_EMBEDDED_SERVER` | `true` | Start the in-JVM mock target |
| `EMBEDDED_PORT` | `18150` | Mock server port |
| `TARGET_BASE_URL` | `http://localhost:18150` | Used when the embedded server is off |
| `THREAD_NUMBER` | `10` | JMeter threads (concurrent virtual users) |
| `DURATION_SECONDS` | `120` | Load duration |
| `RAMP_SECONDS` | `15` | Ramp-up time |
| `THINK_TIME_MS` | `200` | Pause between iterations per thread |
| `SCENARIO` | `All` | `All` \| `Crud` \| `Read` |
| `CRUD_THROUGHPUT` | `10` | % of iterations running the CRUD flow |
| `READ_THROUGHPUT` | `90` | % of iterations running the read flow |
| `RESOURCE_COUNT` | `200` | Baseline items created by `setUpData` |
| `PERCENTAGE_404` | `25` | % of reads that target a non-existent id (expect 404) |
| `STEP_DELAY_MS` | `500` | Delay between chained CRUD steps |
| `SLA` | `60000` | Per-sample Duration Assertion (ms) |

## Pointing at a real service

```bash
./run-jmeter.sh -JSTART_EMBEDDED_SERVER=false -JTARGET_BASE_URL=https://your-service.example.com
```

The client currently speaks a simple CRUD contract (`POST/GET/PUT/DELETE /items`, `GET /items/{id}`).
To hit a different API, edit `ClientRequests.java` (URLs, bodies, expected status ranges) and the
scenario chains in `LoadTest.java`. Everything else — threading, throughput split, ramp, metrics,
404 mixing, reporting — stays the same.

## Build & publish the artifacts

```bash
# 1) rebuild the runnable zip bundle  ->  sample-jmeter-test-bundle.zip
./scripts/build-bundle.sh

# 2) build + push the Docker image  ->  uditgaurav/sample-jmeter-test:{1.0.0,latest}
docker login -u uditgaurav            # once
./scripts/publish.sh
```

`Dockerfile` is a two-stage build: stage 1 (Maven) compiles the shaded fat jar, stage 2 drops
it into a JMeter 5.6.3 runtime image. `docker/entrypoint.sh` runs the plan headless and writes
results to the `/results` volume.

## Verified

Headless JMeter run (20s, 10 threads): **837 samples, 0 errors**, sampler labels
`init, setUpData, crudScenario (≈10%), readScenario (≈90%), teardown`, with latency timers and a
read mix of 75% 200 / 25% 404 matching `PERCENTAGE_404`.
