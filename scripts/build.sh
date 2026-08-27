#!/usr/bin/env bash
# Author: kejiqing
# Build without requiring system Maven (uses local JDK + jars under .tools).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/javac" ]]; then
  if [[ -x "$ROOT/.tools/jdk/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home/bin/javac" ]]; then
    export JAVA_HOME="$ROOT/.tools/jdk/openjdk/26.0.2.1/libexec/openjdk.jdk/Contents/Home"
  fi
fi
export PATH="$JAVA_HOME/bin:$PATH"

GEN="$ROOT/target/gen"
rm -rf "$GEN" target/classes target/test-classes
mkdir -p "$GEN" target/classes target/test-classes

G4DIR="$ROOT/src/main/antlr4/org/apache/doris/nereids"
(
  cd "$G4DIR"
  java -jar "$ROOT/.tools/lib/antlr4-4.9.3-complete.jar" -visitor -listener -package org.apache.doris.nereids \
    -o "$GEN" DorisLexer.g4
  cp "$GEN/DorisLexer.tokens" .
  java -jar "$ROOT/.tools/lib/antlr4-4.9.3-complete.jar" -visitor -listener -package org.apache.doris.nereids \
    -o "$GEN" DorisParser.g4
)

CP_RUNTIME="$ROOT/.tools/lib/antlr4-runtime-4.9.3.jar:$ROOT/.tools/lib/gson-2.11.0.jar:$ROOT/.tools/lib/mysql-connector-j-8.4.0.jar"
find "$GEN" src/main/java -name '*.java' > target/sources.txt
javac --release 17 -encoding UTF-8 -cp "$CP_RUNTIME" -d target/classes @target/sources.txt

# fat jar
mkdir -p target/fat
cd target/fat
jar xf "$ROOT/.tools/lib/antlr4-runtime-4.9.3.jar"
jar xf "$ROOT/.tools/lib/gson-2.11.0.jar"
jar xf "$ROOT/.tools/lib/mysql-connector-j-8.4.0.jar"
cp -R "$ROOT/target/classes/" .
cd "$ROOT"
jar cfe target/doris-sql-err-check-0.1.0-SNAPSHOT.jar io.kejiqing.dorissqlerr.cli.Main -C target/fat .
chmod +x "$ROOT/bin/doris-sql-err-check" 2>/dev/null || true
echo "Built target/doris-sql-err-check-0.1.0-SNAPSHOT.jar"
echo "CLI:  $ROOT/bin/doris-sql-err-check"

# tests
CP_TEST="target/classes:$CP_RUNTIME:$ROOT/.tools/lib/junit-platform-console-standalone-1.10.2.jar"
find src/test/java -name '*.java' > target/test-sources.txt
javac --release 17 -encoding UTF-8 -cp "$CP_TEST" -d target/test-classes @target/test-sources.txt
java -jar .tools/lib/junit-platform-console-standalone-1.10.2.jar \
  execute -cp "target/classes:target/test-classes:$CP_RUNTIME" \
  --scan-classpath target/test-classes
