#!/usr/bin/env bash
# Reproducible build/test entry point for the FB-SMP upgrade work.
#
# This environment ships JDKs whose version strings have four parts
# (e.g. 25.0.4.1). Gradle 8.14.3's bundled Kotlin DSL compiler rejects
# four-part versions, so we launch Gradle on a 3-part JDK (21) and pass
# -Djava.version=<3-part> to satisfy the Kotlin DSL parser. The Java 25
# toolchain is still used for compilation via the toolchain path below.
#
# Usage:  bash _staging/build.sh [gradle-args...]     (default: build -x test)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

GRADLE_JDK="${GRADLE_JDK:-/workspace/jdk21}"
TOOLCHAIN_JDK="${TOOLCHAIN_JDK:-/workspace/jdk25/jdk-25.0.4.1+1}"

export JAVA_HOME="$GRADLE_JDK"
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_OPTS="-Djava.version=21.0.12"

nice -n 19 ./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx1g" \
  -Dorg.gradle.java.installations.paths="$TOOLCHAIN_JDK" \
  "$@"
