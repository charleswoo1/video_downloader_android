param (
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $ScriptDir
$LibsDir = Join-Path $RootDir "app\libs"

$PR361_Repo = "https://github.com/hussain-najeb/youtubedl-android.git"
$PR361_Commit = "76ed1bf9eb6ed2e6858ed88a4ccfbb8a35f292d7"
$PR359_YtDlp_Url = "https://github.com/yausername/youtubedl-android/raw/a505022ad858dcf947ecce56b49a97d5b906fe46/library/src/main/res/raw/ytdlp"
$PR359_YtDlp_Sha256 = "3f1b267b4488f3aed3731a9e84a44011ca5569901868532e10ee11fd07d69707"

if (-not (Test-Path $LibsDir)) {
    New-Item -ItemType Directory -Path $LibsDir | Out-Null
}

$commonAar = Join-Path $LibsDir "common-release.aar"
$libraryAar = Join-Path $LibsDir "library-release.aar"
$ffmpegAar = Join-Path $LibsDir "ffmpeg-release.aar"

if ((Test-Path $commonAar) -and (Test-Path $libraryAar) -and (Test-Path $ffmpegAar) -and (-not $Force)) {
    Write-Host "Runtime AARs already present in $LibsDir. Use -Force to rebuild."
    exit 0
}

$BuildDir = Join-Path $RootDir "build\runtime-repo"
if (Test-Path $BuildDir) {
    Remove-Item -Recurse -Force $BuildDir
}
New-Item -ItemType Directory -Path $BuildDir | Out-Null

Write-Host "Cloning pinned upstream PR #361 repo ($PR361_Commit)..."
Push-Location $BuildDir
try {
    git init . | Out-Null
    git remote add origin $PR361_Repo | Out-Null
    git fetch --depth 1 origin $PR361_Commit | Out-Null
    git checkout FETCH_HEAD | Out-Null

    Write-Host "Downloading pinned yt-dlp binary (PR #359: 2026.08.30.232658)..."
    $rawYtdlpPath = Join-Path $BuildDir "library\src\main\res\raw\ytdlp"
    curl.exe -sSL -o $rawYtdlpPath $PR359_YtDlp_Url
    $hash = (Get-FileHash $rawYtdlpPath -Algorithm SHA256).Hash.ToLower()
    if ($hash -ne $PR359_YtDlp_Sha256) {
        throw "ERROR: SHA256 mismatch for yt-dlp binary: expected $PR359_YtDlp_Sha256, got $hash"
    }
    Write-Host "yt-dlp checksum verified: $hash"

    Write-Host "Patching gradle configuration for build reproducibility..."
    (Get-Content "buildSrc\build.gradle.kts") -replace '8\.13\.0', '8.9.1' | Set-Content "buildSrc\build.gradle.kts"
    (Get-Content "build.gradle.kts") -replace '8\.13\.0', '8.9.1' -replace 'kotlin_version by extra\("1.7.22"\)', 'kotlin_version by extra("2.0.21")' | Where-Object { $_ -notmatch 'jcenter' -and $_ -notmatch 'bintray' } | Set-Content "build.gradle.kts"
    (Get-Content "settings.gradle.kts") | Where-Object { $_ -notmatch 'jcenter' } | Set-Content "settings.gradle.kts"

    $opt = @"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
"@
    foreach ($mod in @("common", "library", "ffmpeg")) {
        $file = "$mod\build.gradle.kts"
        $txt = Get-Content $file -Raw
        if ($txt -notmatch 'JavaVersion\.VERSION_17') {
            $txt = $txt -replace 'buildTypes \{', ($opt + "`n    buildTypes {")
            Set-Content -Path $file -Value $txt
        }
    }

    Write-Host "Building AARs with root Gradle wrapper..."
    $gradlew = Join-Path $RootDir "gradlew.bat"
    & $gradlew --project-dir $BuildDir :common:assembleRelease :library:assembleRelease :ffmpeg:assembleRelease

    Copy-Item "$BuildDir\common\build\outputs\aar\common-release.aar" $commonAar -Force
    Copy-Item "$BuildDir\library\build\outputs\aar\library-release.aar" $libraryAar -Force
    Copy-Item "$BuildDir\ffmpeg\build\outputs\aar\ffmpeg-release.aar" $ffmpegAar -Force

    Write-Host "Runtime AARs successfully generated in $LibsDir."
} finally {
    Pop-Location
}
