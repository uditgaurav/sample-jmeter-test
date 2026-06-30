sample-jmeter-test bundle
=========================

A ready-to-run JMeter load test. JMeter drives the load; a JUnit test class
(com.example.load.LoadTest, inside lib/junit/fat-jmeter-junit-load-test.jar) does the
actual async HTTP work and records latency/throughput metrics.

Contents
--------
  load-test.jmx                         JMeter test plan (thread groups, throughput split, JUnitSamplers)
  load.properties                       all tunable parameters
  lib/junit/fat-...-load-test.jar       the test classes + all dependencies (shaded)
  run.sh                                self-contained headless runner

Requirements
------------
  - Java 11+ (Java 21 recommended)
  - JMeter 5.6.3 (run.sh will use $JMETER_HOME / a jmeter on PATH / Homebrew, or download it)

Run
---
  ./run.sh                              # uses the bundled load.properties (mock server, offline)
  ./run.sh -JDURATION_SECONDS=300 -JTHREAD_NUMBER=50 -JSCENARIO=Read

Point it at a real service
--------------------------
  ./run.sh -JSTART_EMBEDDED_SERVER=false -JTARGET_BASE_URL=https://your-service.example.com

Outputs (written to ./results)
------------------------------
  results/results.jtl                   raw samples
  results/report/index.html             HTML dashboard
  results/jmeter.log                    JMeter log

Key parameters (override with -J)
---------------------------------
  START_EMBEDDED_SERVER (true)          start the in-JVM mock target
  TARGET_BASE_URL (http://localhost:18150)  used when the embedded server is off
  THREAD_NUMBER (10)                    concurrent virtual users
  DURATION_SECONDS (120)               load duration
  RAMP_SECONDS (15)                    ramp-up time
  SCENARIO (All)                       All | Crud | Read
  CRUD_THROUGHPUT (10) / READ_THROUGHPUT (90)   traffic split (%)
  RESOURCE_COUNT (200)                 baseline items created before load
  PERCENTAGE_404 (25)                  % of reads that target a non-existent id
  SLA (60000)                          per-sample Duration Assertion (ms)
