#!/bin/sh
# sbrkcp build script (POSIX sh / Git Bash / WSL / macOS)
# 用法：
#   ./build.sh            编译并打包
#   ./build.sh selftest   编译、打包并跑内置回归自检
#   ./build.sh clean      清理编译输出与 jar
# 依赖：JDK 9+（使用 --release 8 产出 Java 8 兼容字节码）
set -e

ROOT=$(cd "$(dirname "$0")" && pwd)
cd "$ROOT"

if [ -n "$JAVA_HOME" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAR="$JAVA_HOME/bin/jar"
  JAVA="$JAVA_HOME/bin/java"
else
  JAVAC=javac
  JAR=jar
  JAVA=java
fi

clean() {
  echo "清理 build/ 与 dist/*.jar"
  rm -rf build/bootjar build/mods
  rm -f dist/boot.jar dist/main/fuckrkw.jar dist/main/fuckrfw.jar \
        dist/main/fuckrkimg.jar dist/other/fuckbmp0.jar
}

if [ "$1" = "clean" ]; then
  clean
  exit 0
fi

echo "[1/5] 清理旧的编译输出"
rm -rf build/bootjar build/mods
mkdir -p build/bootjar build/mods build/tmp
# dist/ 被 .gitignore 忽略，全新 clone 里不存在。JDK 17 的 jar 不会自动创建
# --file 的父目录，会在 Files.move 处抛 NoSuchFileException，故显式建好。
mkdir -p dist dist/main dist/other

echo "[2/5] 编译 api / core / boot"
find src/api src/core src/boot -name '*.java' > build/tmp/sources_boot.txt
if ! "$JAVAC" --release 8 -encoding UTF-8 -nowarn -d build/bootjar @build/tmp/sources_boot.txt 2>/dev/null; then
  echo "    --release 8 不可用，回退到 -source 8 -target 8"
  "$JAVAC" -source 8 -target 8 -encoding UTF-8 -nowarn -d build/bootjar @build/tmp/sources_boot.txt
fi

"$JAR" --create --file dist/boot.jar --main-class sbrkcp.boot.BootMain -C build/bootjar .

echo "[3/5] 编译模块并打包"
build_module() {
  MOD=$1
  OUTJAR=$2
  SRC="src/modules/$MOD"
  RES="$SRC/resources"
  OD="build/mods/$MOD"
  mkdir -p "$OD"
  find "$SRC" -name '*.java' > "build/tmp/sources_$MOD.txt"
  "$JAVAC" --release 8 -encoding UTF-8 -nowarn -cp dist/boot.jar -d "$OD" @"build/tmp/sources_$MOD.txt"
  [ -d "$RES" ] && cp -R "$RES"/. "$OD"/
  "$JAR" --create --file "$OUTJAR" -C "$OD" .
  echo "    $MOD -> $OUTJAR"
}

build_module fuckrkw   dist/main/fuckrkw.jar
build_module fuckrfw   dist/main/fuckrfw.jar
build_module fuckrkimg dist/main/fuckrkimg.jar
build_module fuckbmp0  dist/other/fuckbmp0.jar

echo "[4/5] 校验产物"
mkdir -p dist/main/fuckit/rockchip
[ -f dist/main/fuckit/rockchip/.gitkeep ] || : > dist/main/fuckit/rockchip/.gitkeep
ls -l dist/boot.jar dist/main/*.jar dist/other/*.jar

echo "[5/5] 模块加载自检"
"$JAVA" -Dfile.encoding=UTF-8 -Dsbrkcp.base="$ROOT/dist" \
  -jar dist/boot.jar --list-modules

if [ "$1" = "selftest" ]; then
  echo
  echo "== 回归自检 =="
  "$JAVA" -Dfile.encoding=UTF-8 -Dsbrkcp.base="$ROOT/dist" \
    -jar dist/boot.jar --selftest --samples "$ROOT/samples"
fi

echo
echo "构建完成：$ROOT/dist"
