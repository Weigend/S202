#!/bin/bash
set -e
cd "$(dirname "$0")"

pandoc software-architektur-fuer-ai-anwendungen.md \
  -o software-architektur-fuer-ai-anwendungen.html \
  --standalone \
  --embed-resources \
  -V lang=de \
  --highlight-style=tango

google-chrome --headless --disable-gpu \
  --print-to-pdf=software-architektur-fuer-ai-anwendungen.pdf \
  --print-to-pdf-no-header \
  --no-sandbox \
  "file://$(pwd)/software-architektur-fuer-ai-anwendungen.html"

echo "Done: software-architektur-fuer-ai-anwendungen.pdf"
