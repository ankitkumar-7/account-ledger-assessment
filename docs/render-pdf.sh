#!/usr/bin/env bash
# Renders docs/architecture.html to the submitted PDF using headless Google Chrome (macOS path).
set -euo pipefail
cd "$(dirname "$0")"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
"$CHROME" --headless=new --disable-gpu --no-pdf-header-footer \
  --print-to-pdf="$PWD/architecture-and-trade-offs.pdf" "file://$PWD/architecture.html"
