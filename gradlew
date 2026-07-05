#!/bin/sh

# Gradle wrapper script
DIRNAME=$(dirname "$0")
JARFILE="$DIRNAME/gradle/wrapper/gradle-wrapper.jar"
if [ -f "$JARFILE" ]; then
    java -jar "$JARFILE" "$@"
else
    echo "Error: Could not find gradle wrapper jar"
    exit 1
fi
