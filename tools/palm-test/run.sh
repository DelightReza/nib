#!/bin/bash
# Desktop check for PalmFilter: scripted touch scenarios (no Android needed). Run: bash tools/palm-test/run.sh
set -e
cd "$(dirname "$0")/../.."
out=$(mktemp -d)
javac -d "$out" src/app/nib/notes/PalmFilter.java tools/palm-test/PalmTest.java
java -cp "$out" app.nib.notes.PalmTest
