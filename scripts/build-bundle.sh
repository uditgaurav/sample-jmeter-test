#!/usr/bin/env bash
#
# Build the runnable zip bundle: the JMeter plan + properties + the shaded test jar + a
# self-contained runner, zipped so it can be downloaded from the repo and run directly
# (no Maven/build step required on the consuming side).
#
# Usage:  ./scripts/build-bundle.sh           # produces sample-jmeter-test-bundle.zip
#         VERSION=1.2.0 ./scripts/build-bundle.sh
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"

VERSION="${VERSION:-1.0.0}"
NAME="sample-jmeter-test"
STAGE="dist/${NAME}-${VERSION}"
ZIP="${NAME}-bundle.zip"

# ---- Java -----------------------------------------------------------------------------------
if ! command -v java >/dev/null 2>&1; then
  for c in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
           /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home; do
    if [ -x "$c/bin/java" ]; then export JAVA_HOME="$c"; export PATH="$JAVA_HOME/bin:$PATH"; break; fi
  done
fi
echo "==> Using java: $(command -v java)"

# ---- Build the fat jar ----------------------------------------------------------------------
echo "==> Building fat jar (mvn package)"
mvn -q -DskipTests package
FAT="target/fat-jmeter-junit-load-test.jar"
[ -f "$FAT" ] || { echo "ERROR: $FAT not found"; exit 1; }

# ---- Assemble the bundle --------------------------------------------------------------------
echo "==> Assembling bundle in $STAGE"
rm -rf "$STAGE"
mkdir -p "$STAGE/lib/junit"
cp "$FAT"                       "$STAGE/lib/junit/"
cp "src/main/jmeter/load-test.jmx" "$STAGE/load-test.jmx"
cp "load.properties"           "$STAGE/load.properties"
cp "scripts/bundle/run.sh"     "$STAGE/run.sh"
cp "scripts/bundle/README.txt" "$STAGE/README.txt"
chmod +x "$STAGE/run.sh"

# ---- Zip ------------------------------------------------------------------------------------
echo "==> Zipping -> $ZIP"
rm -f "$ZIP"
( cd dist && zip -qr "../$ZIP" "${NAME}-${VERSION}" )

echo
echo "Done. Bundle contents:"
unzip -l "$ZIP"
echo
echo "Created: $HERE/$ZIP"
