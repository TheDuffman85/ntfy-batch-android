#!/usr/bin/env bash

set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

java_home_for_compiler() {
    local compiler
    local version
    local gradle_user_home

    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
        compiler="$JAVA_HOME/bin/javac"
        version="$("$compiler" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')"
        if [[ "$version" =~ ^[0-9]+$ ]] && (( version >= 17 )); then
            printf '%s\n' "$JAVA_HOME"
            return 0
        fi
    fi

    if compiler="$(command -v javac 2>/dev/null)"; then
        version="$("$compiler" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')"
        if [[ "$version" =~ ^[0-9]+$ ]] && (( version >= 17 )); then
            (cd -- "$(dirname -- "$compiler")/.." && pwd)
            return 0
        fi
    fi

    gradle_user_home="${GRADLE_USER_HOME:-$(cd ~ && pwd)/.gradle}"
    while IFS= read -r compiler; do
        version="$("$compiler" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')"
        if [[ "$version" =~ ^[0-9]+$ ]] && (( version >= 17 )); then
            (cd -- "$(dirname -- "$compiler")/.." && pwd)
            return 0
        fi
    done < <(find "$gradle_user_home/jdks" -mindepth 3 -maxdepth 3 -type f -name javac -executable -print 2>/dev/null | sort)

    return 1
}

if ! java_home="$(java_home_for_compiler)"; then
    printf 'A JDK 17 or newer with javac is required. No compatible JDK was found on PATH or in the Gradle cache.\n' >&2
    exit 1
fi

export JAVA_HOME="$java_home"
export PATH="$JAVA_HOME/bin:$PATH"

android_sdk_for_build() {
    local sdk_root
    local user_home
    local candidate
    local platform_dir
    local sdk_candidates=()

    if [[ -n "${ANDROID_HOME:-}" ]]; then
        sdk_candidates+=("$ANDROID_HOME")
    fi
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        sdk_candidates+=("$ANDROID_SDK_ROOT")
    fi

    user_home="$(cd ~ && pwd)"
    sdk_candidates+=(
        "$user_home/Android/Sdk"
        "$user_home/Android/sdk"
        "$user_home/Library/Android/sdk"
        "/opt/android-sdk"
        "/opt/android-sdk-linux"
    )

    for candidate in "${sdk_candidates[@]}"; do
        sdk_root="${candidate%/}"
        if [[ -d "$sdk_root/platforms/android-35" ]]; then
            printf '%s\n' "$sdk_root"
            return 0
        fi
    done

    while IFS= read -r platform_dir; do
        sdk_root="$(cd -- "$(dirname -- "$platform_dir")/.." && pwd)"
        printf '%s\n' "$sdk_root"
        return 0
    done < <(find /tmp -type d -path '*/platforms/android-35' -print 2>/dev/null | sort)

    return 1
}

if [[ ! -f "$project_root/local.properties" ]] || ! grep -q '^sdk\.dir=' "$project_root/local.properties"; then
    if ! android_sdk_root="$(android_sdk_for_build)"; then
        printf 'Android SDK platform 35 was not found. Set ANDROID_HOME or ANDROID_SDK_ROOT, or install platform 35.\n' >&2
        exit 1
    fi

    export ANDROID_HOME="$android_sdk_root"
    export ANDROID_SDK_ROOT="$android_sdk_root"
fi

cd "$project_root"
"$project_root/gradlew" :app:assembleDebug "$@"

apk_path="$project_root/app/build/outputs/apk/debug/app-debug.apk"
printf 'Built APK: %s\n' "$apk_path"
