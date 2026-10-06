#!/usr/bin/env bash
# Compiles and runs the ledger with only a JDK (21+). No build tool required.
#   ./run.sh         replay the event stream and print the per-day report
#   ./run.sh test    run the test suite
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out
mkdir -p out
javac -d out $(find src/main/java src/test/java -name '*.java')

case "${1:-replay}" in
  replay) java -cp out ledger.Replay ;;
  test)   java -cp out ledger.LedgerTests ;;
  *)      echo "usage: ./run.sh [replay|test]" >&2; exit 2 ;;
esac
