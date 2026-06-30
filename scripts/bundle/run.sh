#!/usr/bin/env bash
#
# Self-contained runner shipped inside sample-jmeter-test-bundle.zip.
# Unzip the bundle, then:
#
#   ./run.sh                                   # run with the bundled load.properties
#   ./run.sh -JDURATION_SECONDS=300 -JTHREAD_NUMBER=50 -JSCENARIO=Read
#   ./run.sh -JSTART_EMBEDDED_SERVER=false -JTARGET_BASE_URL=https://your-service.example.com
#
# It finds a JMeter install (JMETER_HOME, then PATH, then Homebrew); if none is found it
# downloads Apache JMeter 5.6.3 next to this script. The bundled test jar is copied into
# JMeter's lib/junit so the JUnitSampler can load it.
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
JMX="$HERE/load-test.jmx"
PROPS="$HERE/load.properties"
JAR="$(ls "$HERE"/lib/junit/*.jar | head -1)"
OUT="${OUT:-$HERE/results}"
JMETER_VERSION="5.6.3"

# ---- locate JMeter --------------------------------------------------------------------------
if [ -n "${JMETER_HOME:-}" ] && [ -x "$JMETER_HOME/bin/jmeter" ]; then
  :
elif command -v jmeter >/dev/null 2>&1; then
  JMETER_HOME="$(cd "$(dirname "$(command -v jmeter)")/.." && pwd)"
elif command -v brew >/dev/null 2>&1 && brew --prefix jmeter >/dev/null 2>&1; then
  JMETER_HOME="$(brew --prefix jmeter)/libexec"
else
  JMETER_HOME="$HERE/.jmeter/apache-jmeter-$JMETER_VERSION"
  if [ ! -x "$JMETER_HOME/bin/jmeter" ]; then
    echo "==> Downloading Apache JMeter $JMETER_VERSION"
    mkdir -p "$HERE/.jmeter"
    curl -fsSL "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-$JMETER_VERSION.tgz" \
      -o "$HERE/.jmeter/jmeter.tgz"
    tar -xzf "$HERE/.jmeter/jmeter.tgz" -C "$HERE/.jmeter"
  fi
fi
echo "==> JMeter home: $JMETER_HOME"

# ---- install the test jar where the JUnitSampler can load it --------------------------------
mkdir -p "$JMETER_HOME/lib/junit"
cp -f "$JAR" "$JMETER_HOME/lib/junit/"

# ---- run headless ---------------------------------------------------------------------------
mkdir -p "$OUT"
rm -f "$OUT/results.jtl" "$OUT/jmeter.log"
rm -rf "$OUT/report"

"$JMETER_HOME/bin/jmeter" -n \
  -t "$JMX" \
  -q "$PROPS" \
  -Juser.classpath="$JAR" \
  -l "$OUT/results.jtl" \
  -j "$OUT/jmeter.log" \
  -e -o "$OUT/report" \
  "$@"

echo
echo "Done."
echo "  Raw results : $OUT/results.jtl"
echo "  HTML report : $OUT/report/index.html"
echo "  JMeter log  : $OUT/jmeter.log"
