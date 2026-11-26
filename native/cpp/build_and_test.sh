#!/bin/bash
# Builds the C++ core with warnings + sanitizers and runs it against the Java conformance vectors.
set -e
cd "$(dirname "$0")"
g++ -std=c++17 -O1 -g -Wall -Wextra -Wpedantic -Wconversion -fsanitize=address,undefined -fno-sanitize-recover=all \
    test_core.cpp -o test_core_san
./test_core_san ../../conformance/vectors.txt
