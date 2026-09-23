#!/bin/bash
# ==============================================================================
# JettraDB High-Performance JVM Launch Script
# Configured for Java 25+ with ultra-low latency ZGC and Compact Object Headers
# ==============================================================================

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$DIR"

JAR_FILE="target/JettraDB-1.0-SNAPSHOT.jar"
if [ ! -f "$JAR_FILE" ]; then
    JAR_FILE="target/JettraStoreEngine-1.0-SNAPSHOT.jar"
fi

if [ ! -f "$JAR_FILE" ]; then
    echo "Artifact not found. Compiling JettraDB..."
    mvn clean package -DskipTests
fi

# JVM Tuning Parameters:
# -XX:+UseZGC: Ultra-low latency garbage collection (< 1ms pause times)
# -XX:+UseCompactObjectHeaders: Reduces memory footprint by up to 20% on Java 25+
# --enable-preview: Enables Java 25 language and virtual thread features
# -Xms512m -Xmx4g: Scalable heap limits
JVM_OPTS="-XX:+UseZGC -XX:+UseCompactObjectHeaders --enable-preview -Xms512m -Xmx4g"

echo "===================================================================="
echo " Starting JettraDB"
echo " JVM Collector: ZGC (Low-Latency Concurrent GC)"
echo " Memory Optimization: Compact Object Headers (-XX:+UseCompactObjectHeaders)"
echo " Serialization: JettraSerialization (JettraEE Native)"
echo " Runtime: OpenJDK Java 25+"
echo "===================================================================="

exec java $JVM_OPTS -jar "$JAR_FILE" "$@"
