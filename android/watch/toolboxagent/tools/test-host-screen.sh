#!/bin/sh
set -eu
library=$1
java_root=$2
output=$3
module_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$output/classes"
"$java_root/bin/javac" -d "$output/classes" \
  "$module_root/src/main/java/dev/ticbox/companion/toolboxagent/ScreenNative.java" \
  "$module_root/src/test/native/ScreenNativeTest.java"
"$java_root/bin/java" --enable-native-access=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp "$output/classes" \
  dev.ticbox.companion.toolboxagent.ScreenNativeTest "$library"
cc -std=c11 -O2 -Wall -Wextra -Werror -pthread -fsanitize=address,undefined -fno-omit-frame-pointer \
  -DTW_SCREEN_NO_JNI -DTW_SCREEN_TEST -I"$module_root/src/main/cpp" \
  "$module_root/src/main/cpp/screen_udp.c" "$module_root/src/main/cpp/screen_fec.c" \
  "$module_root/src/test/native/screen_native_test.c" -o "$output/screen-native-test"
timeout 20 "$output/screen-native-test"
printf 'passed\n' > "$output/passed.txt"
