#!/bin/bash
set -e

echo "Building JettraDB with Java 25+..."
mvn clean package -DskipTests

JAR_FILE="target/JettraDB-1.0-SNAPSHOT.jar"
if [ ! -f "$JAR_FILE" ]; then
    JAR_FILE="target/JettraStoreEngine-1.0-SNAPSHOT.jar"
fi

echo "Launching JettraDB with ZGC and Compact Object Headers (-XX:+UseZGC -XX:+UseCompactObjectHeaders --enable-preview)..."
java -XX:+UseZGC -XX:+UseCompactObjectHeaders --enable-preview -jar "$JAR_FILE"