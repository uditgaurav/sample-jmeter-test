#!/usr/bin/env bash
#
# Container entrypoint: run the JMeter plan headless and write results to /results.
# Any extra arguments are passed straight through to JMeter, so you can override
# properties at run time, e.g.:
#
#   docker run --rm uditgaurav/sample-jmeter-test:latest \
#       -JDURATION_SECONDS=300 -JTHREAD_NUMBER=50 -JSCENARIO=Read \
#       -JSTART_EMBEDDED_SERVER=false -JTARGET_BASE_URL=https://your-service.example.com
#
set -euo pipefail

JMX="${JMX:-/test/load-test.jmx}"
PROPS="${PROPS:-/test/load.properties}"
OUT="${OUT:-/results}"

JAR="$(ls "${JMETER_HOME}"/lib/junit/*.jar 2>/dev/null | head -1)"

mkdir -p "$OUT"
rm -f "$OUT/results.jtl" "$OUT/jmeter.log"
rm -rf "$OUT/report"

echo "==> JMeter:   $(command -v jmeter)"
echo "==> Plan:     $JMX"
echo "==> Props:    $PROPS"
echo "==> Test jar: $JAR"
echo "==> Output:   $OUT"
echo "==> Extra args: $*"

exec jmeter -n \
  -t "$JMX" \
  -q "$PROPS" \
  -Juser.classpath="$JAR" \
  -l "$OUT/results.jtl" \
  -j "$OUT/jmeter.log" \
  -e -o "$OUT/report" \
  "$@"
