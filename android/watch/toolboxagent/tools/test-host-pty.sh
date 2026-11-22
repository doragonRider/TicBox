#!/bin/sh
set -eu
library=$1
java_root=$2
output=$3
module_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$output/classes"
"$java_root/bin/javac" -d "$output/classes" \
  "$module_root/src/main/java/dev/ticbox/companion/toolboxagent/PtyNative.java" \
  "$module_root/src/test/native/PtyNativeTest.java"
"$java_root/bin/java" --enable-native-access=ALL-UNNAMED -cp "$output/classes" \
  dev.ticbox.companion.toolboxagent.PtyNativeTest "$library"
printf 'passed\n' > "$output/passed.txt"
