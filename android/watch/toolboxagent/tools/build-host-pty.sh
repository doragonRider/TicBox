#!/bin/sh
set -eu
output=$1
java_root=$2
module_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$(dirname -- "$output")"
cc -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared -pthread \
  -I"$java_root/include" -I"$java_root/include/linux" \
  -Wl,-z,max-page-size=16384 -Wl,--build-id=none \
  "$module_root/src/main/cpp/pty.c" "$module_root/src/main/cpp/screen_udp.c" "$module_root/src/main/cpp/screen_fec.c" -o "$output"
