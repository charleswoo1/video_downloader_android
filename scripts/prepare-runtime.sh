#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LIBS_DIR="${ROOT_DIR}/app/libs"

PR361_REPO="https://github.com/hussain-najeb/youtubedl-android.git"
PR361_COMMIT="76ed1bf9eb6ed2e6858ed88a4ccfbb8a35f292d7"
PR359_YTDLP_URL="https://github.com/yausername/youtubedl-android/raw/a505022ad858dcf947ecce56b49a97d5b906fe46/library/src/main/res/raw/ytdlp"
PR359_YTDLP_SHA256="3f1b267b4488f3aed3731a9e84a44011ca5569901868532e10ee11fd07d69707"

mkdir -p "${LIBS_DIR}"

if [[ -f "${LIBS_DIR}/common-release.aar" && -f "${LIBS_DIR}/library-release.aar" && -f "${LIBS_DIR}/ffmpeg-release.aar" && "${1:-}" != "--force" ]]; then
    echo "Runtime AARs already present in ${LIBS_DIR}. Use --force to rebuild."
    exit 0
fi

BUILD_DIR="${ROOT_DIR}/build/runtime-repo"
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

echo "Cloning pinned upstream PR #361 repo (${PR361_COMMIT})..."
git init "${BUILD_DIR}"
cd "${BUILD_DIR}"
git remote add origin "${PR361_REPO}"
git fetch --depth 1 origin "${PR361_COMMIT}"
git checkout FETCH_HEAD

echo "Downloading pinned yt-dlp binary (PR #359: 2026.08.30.232658)..."
curl -sSL -o "library/src/main/res/raw/ytdlp" "${PR359_YTDLP_URL}"
ACTUAL_SHA256=$(sha256sum "library/src/main/res/raw/ytdlp" | awk '{print $1}')
if [[ "${ACTUAL_SHA256}" != "${PR359_YTDLP_SHA256}" ]]; then
    echo "ERROR: SHA256 mismatch for yt-dlp binary: expected ${PR359_YTDLP_SHA256}, got ${ACTUAL_SHA256}"
    exit 1
fi
echo "yt-dlp checksum verified: ${ACTUAL_SHA256}"

echo "Patching gradle configuration for build reproducibility..."
sed -i 's/8\.13\.0/8.9.1/g' buildSrc/build.gradle.kts
sed -i '/jcenter/d' settings.gradle.kts || true

python3 -c "
import re, sys

content = open('build.gradle.kts').read()
content = content.replace('8.13.0', '8.9.1')
content = content.replace('kotlin_version by extra(\"1.7.22\")', 'kotlin_version by extra(\"2.0.21\")')
# Remove entire legacy JCenter maven block
content = re.sub(r'(?s)maven\s*\{[^}]*(?:jcenter|bintray)[^}]*\}', '', content)
open('build.gradle.kts', 'w').write(content)

# Sanity validation: ensure no jcenter/bintray and no URL-less maven blocks
if 'jcenter' in content or 'bintray' in content:
    print('ERROR: jcenter/bintray still present in build.gradle.kts', file=sys.stderr)
    sys.exit(1)

for m in re.finditer(r'(?s)maven\s*\{([^}]*)\}', content):
    body = m.group(1)
    if 'url' not in body and 'uri' not in body:
        print(f'ERROR: URL-less maven repository block found: {m.group(0)}', file=sys.stderr)
        sys.exit(1)

print('Sanity validation passed: build.gradle.kts has no empty or URL-less maven blocks.')
"

for mod in common library ffmpeg; do
    python3 -c "
content = open('${mod}/build.gradle.kts').read()
if 'JavaVersion.VERSION_17' not in content:
    opt = '''
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = \"17\"
    }
'''
    content = content.replace('buildTypes {', opt + '\n    buildTypes {')
    open('${mod}/build.gradle.kts', 'w').write(content)
"
done

echo "Building AARs with root Gradle wrapper..."
chmod +x "${ROOT_DIR}/gradlew"
"${ROOT_DIR}/gradlew" --project-dir "${BUILD_DIR}" :common:assembleRelease :library:assembleRelease :ffmpeg:assembleRelease

cp "${BUILD_DIR}/common/build/outputs/aar/common-release.aar" "${LIBS_DIR}/common-release.aar"
cp "${BUILD_DIR}/library/build/outputs/aar/library-release.aar" "${LIBS_DIR}/library-release.aar"
cp "${BUILD_DIR}/ffmpeg/build/outputs/aar/ffmpeg-release.aar" "${LIBS_DIR}/ffmpeg-release.aar"

echo "Runtime AARs successfully generated in ${LIBS_DIR}:"
ls -lh "${LIBS_DIR}"
