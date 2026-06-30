# syntax=docker/dockerfile:1
#
# Multi-stage image for the JMeter + JUnit load test.
#   stage 1 (build)   : compile the project and produce the shaded "fat" jar with Maven.
#   stage 2 (runtime) : a JMeter install with our test jar dropped into lib/junit, so the
#                       JUnitSampler can load com.example.load.LoadTest and all its deps.
#
# Build:   docker build -t uditgaurav/sample-jmeter-test:latest .
# Run:     docker run --rm -v "$PWD/out:/results" uditgaurav/sample-jmeter-test:latest -JDURATION_SECONDS=30
#
# By default the container starts an in-JVM mock server and load-tests it (fully offline).
# Point it at a real service:
#   docker run --rm uditgaurav/sample-jmeter-test:latest \
#       -JSTART_EMBEDDED_SERVER=false -JTARGET_BASE_URL=https://your-service.example.com

# ---- stage 1: build the fat jar -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Resolve dependencies first so they cache independently of source changes.
COPY pom.xml ./
RUN mvn -q -e -B -DskipTests dependency:go-offline || true

COPY src ./src
RUN mvn -q -e -B -DskipTests package \
    && cp target/fat-jmeter-junit-load-test.jar /tmp/test.jar

# ---- stage 2: JMeter runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

ARG JMETER_VERSION=5.6.3
ENV JMETER_HOME="/opt/apache-jmeter-${JMETER_VERSION}" \
    PATH="/opt/apache-jmeter-${JMETER_VERSION}/bin:${PATH}"

# Install JMeter from the Apache archive (stable, version-pinned).
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates; \
    rm -rf /var/lib/apt/lists/*; \
    curl -fsSL "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" -o /tmp/jmeter.tgz; \
    tar -xzf /tmp/jmeter.tgz -C /opt; \
    rm /tmp/jmeter.tgz; \
    mkdir -p "${JMETER_HOME}/lib/junit"

# Drop the test jar where JMeter's JUnitSampler discovers test classes (and all shaded deps).
COPY --from=build /tmp/test.jar "${JMETER_HOME}/lib/junit/fat-jmeter-junit-load-test.jar"

# The plan + default parameters.
WORKDIR /test
COPY src/main/jmeter/load-test.jmx /test/load-test.jmx
COPY load.properties               /test/load.properties
COPY docker/entrypoint.sh          /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# Results (raw .jtl, jmeter.log, HTML report) are written here; mount a volume to keep them.
VOLUME ["/results"]

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
