#!/usr/bin/env bash
#
# Build the fat jar, install it into JMeter's lib/junit, and run the plan headless.
#
# Usage:
#   ./run-jmeter.sh                     # uses load.properties
#   ./run-jmeter.sh -JTHREAD_NUMBER=50  # override any property on the CLI
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
JMX="$HERE/src/main/jmeter/load-test.jmx"
PROPS="$HERE/load.properties"
OUT="$HERE/target"

# ---- Java -----------------------------------------------------------------------------------
if ! command -v java >/dev/null 2>&1; then
  for c in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
           /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home; do
    if [ -x "$c/bin/java" ]; then export JAVA_HOME="$c"; export PATH="$JAVA_HOME/bin:$PATH"; break; fi
  done
fi
echo "Using java: $(command -v java)"

# ---- Build the fat jar ----------------------------------------------------------------------
echo "==> Building fat jar (mvn package)"
( cd "$HERE" && mvn -q -DskipTests package )
FAT="$(ls "$HERE"/target/fat-jmeter-junit-load-test.jar)"
echo "Built: $FAT"

# ---- Locate JMeter --------------------------------------------------------------------------
if [ -n "${JMETER_HOME:-}" ] && [ -x "$JMETER_HOME/bin/jmeter" ]; then
  :
elif command -v brew >/dev/null 2>&1 && brew --prefix jmeter >/dev/null 2>&1; then
  JMETER_HOME="$(brew --prefix jmeter)/libexec"
else
  # Fall back to a project-local download.
  JV="5.6.3"
  JMETER_HOME="$HERE/.jmeter/apache-jmeter-$JV"
  if [ ! -x "$JMETER_HOME/bin/jmeter" ]; then
    echo "==> Downloading Apache JMeter $JV"
    mkdir -p "$HERE/.jmeter"
    curl -fsSL "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-$JV.tgz" \
      -o "$HERE/.jmeter/jmeter.tgz"
    tar -xzf "$HERE/.jmeter/jmeter.tgz" -C "$HERE/.jmeter"
  fi
fi
echo "Using JMeter home: $JMETER_HOME"

# ---- Install the test jar where the JUnitSampler can load it --------------------------------
mkdir -p "$JMETER_HOME/lib/junit"
cp -f "$FAT" "$JMETER_HOME/lib/junit/"
echo "Installed test jar into $JMETER_HOME/lib/junit/"

# ---- Run headless ---------------------------------------------------------------------------
mkdir -p "$OUT"
rm -f "$OUT/results.jtl" "$OUT/jmeter.log"
rm -rf "$OUT/report"

echo "==> Running JMeter"
"$JMETER_HOME/bin/jmeter" -n \
  -t "$JMX" \
  -q "$PROPS" \
  -Juser.classpath="$FAT" \
  -l "$OUT/results.jtl" \
  -j "$OUT/jmeter.log" \
  -e -o "$OUT/report" \
  "$@"

echo
echo "Done."
echo "  Raw results : $OUT/results.jtl"
echo "  HTML report : $OUT/report/index.html"
echo "  JMeter log  : $OUT/jmeter.log"
