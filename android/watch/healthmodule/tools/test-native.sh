#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror -Isrc/main/cpp src/test/native/bootstrap_policy_test.cpp -o "$1"
"$1"
