#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v kotlinc >/dev/null || { echo 'Install Kotlin kotlinc to run exporter tests.' >&2; exit 1; }
BIRDLOG_SAMPLE_DIR="$PWD/examples" kotlinc tests/work/StubModels.kt android/app/src/main/java/org/birdlog/app/Exporters.kt tests/work/RunExportTests.kt -include-runtime -d /tmp/birdlog-export-tests.jar
BIRDLOG_SAMPLE_DIR="$PWD/examples" java -jar /tmp/birdlog-export-tests.jar
