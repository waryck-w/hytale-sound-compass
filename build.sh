#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
game_dir="${HYTALE_GAME_DIR:-$HOME/.var/app/com.hypixel.HytaleLauncher/data/Hytale/install/release/package/game/latest}"
server_jar="${HYTALE_SERVER_JAR:-$game_dir/Server/HytaleServer.jar}"
export HYTALE_ASSETS="${HYTALE_ASSETS:-$game_dir/Assets.zip}"
jdk_path="${SOUND_COMPASS_JDK:-${JAVA_HOME:-}}"
if [[ -z "$jdk_path" ]]; then
  if command -v javac >/dev/null 2>&1; then
    jdk_path="$(dirname -- "$(dirname -- "$(readlink -f -- "$(command -v javac)")")")"
  elif [[ -x /usr/lib/jvm/java-25-openjdk/bin/javac ]]; then
    jdk_path=/usr/lib/jvm/java-25-openjdk
  fi
fi
if [[ ! -f "$server_jar" || ! -x "$jdk_path/bin/javac" ]]; then
  echo 'Set HYTALE_SERVER_JAR to your Hytale 0.7.2 server JAR and SOUND_COMPASS_JDK to a Java 25 JDK.' >&2
  exit 1
fi
mkdir -p build/classes build/test-classes dist
mod_version="$(python3 -c 'import json; print(json.load(open("src/main/resources/manifest.json"))["Version"])')"
python3 tools/generate_resources.py
mapfile -t sources < <(find src/main/java -name '*.java' -print | sort)
"$jdk_path/bin/javac" --release 25 -encoding UTF-8 -cp "$server_jar" -d build/classes "${sources[@]}"
mapfile -t tests < <(find src/test/java -name '*.java' -print | sort)
"$jdk_path/bin/javac" --release 25 -encoding UTF-8 -cp "build/classes:$server_jar" -d build/test-classes "${tests[@]}"
"$jdk_path/bin/java" -cp "build/test-classes:build/classes:$server_jar" dev.deaftale.compass.CueEngineTest
"$jdk_path/bin/jar" --create --file "dist/SoundCompass-$mod_version.jar" -C build/classes . -C src/main/resources .
python3 tools/validate_package.py "dist/SoundCompass-$mod_version.jar"
echo "Built dist/SoundCompass-$mod_version.jar"
