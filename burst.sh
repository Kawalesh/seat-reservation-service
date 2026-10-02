#!/usr/bin/env bash
# One-command high-concurrency burst runner
# Usage: ./burst.sh [BASE_URL]
set -e

BASE_URL="${1:-http://localhost:8080}"
echo "Running on-sale stampede burst benchmark against: $BASE_URL"

if command -v python3 &>/dev/null; then
    python3 burst.py "$BASE_URL"
elif command -v python &>/dev/null; then
    python burst.py "$BASE_URL"
else
    echo "Error: Python 3 is required to run the benchmark script."
    exit 1
fi
