#!/usr/bin/env bash
# Build BuildKit without Gradle or Maven: ECJ (or javac) + jar packaging.
#
#   bash build.sh [PLUGINS_DIR]
#
# Environment overrides:
#   ECJ=/path/to/ecj.jar        compiler jar; falls back to javac when unset/missing
#   PAPER_API=/path/to/paper-api.jar   required
#   LIB_DIR=/path/to/server/libraries  optional: adventure/gson/bungee-chat jars
#   PLUGINS_DIR=/path/to/plugins       where to copy BuildKit.jar (default: ./build/plugins)
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_PLUGINS="${1:-${PLUGINS_DIR:-$PROJ/build/plugins}}"
ECJ="${ECJ:-$PROJ/tools/ecj.jar}"
API="${PAPER_API:-$PROJ/tools/paper-api.jar}"

if [ ! -f "$API" ]; then
  echo "!! paper-api.jar not found: $API" >&2
  echo "   set PAPER_API=/path/to/paper-api.jar (it ships in your server's libraries/)" >&2
  exit 1
fi

CP="$API"
if [ -n "${LIB_DIR:-}" ] && [ -d "$LIB_DIR" ]; then
  # весь libraries сервера: paper-api тянет guava, adventure, gson и аннотации из него
  EXTRA="$(find "$LIB_DIR" -name '*.jar' 2>/dev/null | sort | tr '\n' ':')"
  CP="$CP:${EXTRA%:}"
fi

# paper-api signatures reference org.jetbrains.annotations; javac needs the jar on the
# classpath even though the annotation is only compile-time. ECJ is lenient, javac is not.
if ! echo "$CP" | tr ':' '\n' | grep -q 'annotations-'; then
  ANN="$PROJ/build/annotations-24.1.0.jar"
  if [ ! -f "$ANN" ]; then
    mkdir -p "$PROJ/build"
    curl -sfL -o "$ANN" \
      https://repo1.maven.org/maven2/org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar \
      || { echo "!! cannot fetch $ANN — set LIB_DIR or put annotations-*.jar there" >&2; exit 1; }
  fi
  CP="$CP:$ANN"
fi

cd "$PROJ"
rm -rf build/classes
mkdir -p build/classes

echo "== compile: $( [ -f "$ECJ" ] && echo "ECJ ($ECJ)" || echo javac )"
echo "   api: $API"
if [ -f "$ECJ" ]; then
  java -jar "$ECJ" -cp "$CP" -d build/classes -encoding UTF-8 -source 21 -target 21 -nowarn \
    src/main/java/com/nous/buildkit/*.java
else
  javac -cp "$CP" -d build/classes -encoding UTF-8 --release 21 -nowarn \
    src/main/java/com/nous/buildkit/*.java
fi

echo "== package jar"
python3 - "$PROJ" "$OUT_PLUGINS" <<'PY'
import os, shutil, sys, zipfile

proj, out_plugins = sys.argv[1], sys.argv[2]
classes = os.path.join(proj, "build", "classes")
shutil.copy(os.path.join(proj, "src", "main", "resources", "plugin.yml"),
            os.path.join(classes, "plugin.yml"))
jar_out = os.path.join(proj, "build", "BuildKit.jar")
if os.path.exists(jar_out):
    os.remove(jar_out)
with zipfile.ZipFile(jar_out, "w", zipfile.ZIP_DEFLATED) as zf:
    zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
    zf.write(os.path.join(classes, "plugin.yml"), "plugin.yml")
    for root, _dirs, files in os.walk(os.path.join(classes, "com")):
        for f in sorted(files):
            p = os.path.join(root, f)
            zf.write(p, os.path.relpath(p, classes))
os.makedirs(out_plugins, exist_ok=True)
target = os.path.join(out_plugins, "BuildKit.jar")
shutil.copy(jar_out, target)
print("done:", target, os.path.getsize(jar_out), "bytes")
PY
