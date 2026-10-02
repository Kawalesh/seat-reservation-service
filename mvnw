#!/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -x "$DIR/tools/apache-maven-3.9.9/bin/mvn" ]; then
    exec "$DIR/tools/apache-maven-3.9.9/bin/mvn" "$@"
fi
exec mvn "$@"
