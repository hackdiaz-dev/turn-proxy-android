#!/bin/bash
# Собирает helper под ABI приложения в app/src/main/jniLibs/<abi>/libtether.so.
# Это исполняемый файл, а не библиотека: имя lib*.so нужно, чтобы Android распаковал его
# в nativeLibraryDir, откуда процесс Shizuku может его запустить.
set -euo pipefail
cd "$(dirname "$0")"
OUT=../app/src/main/jniLibs
build() { # build <abi> <goarch> [goarm]
  mkdir -p "$OUT/$1"
  GOOS=linux GOARCH=$2 GOARM=${3:-} CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o "$OUT/$1/libtether.so" .
  echo "tether: $1 $(du -h "$OUT/$1/libtether.so" | cut -f1)"
}
build arm64-v8a arm64
build armeabi-v7a arm 7
