# ==============================================================================
# GNFL NOTE V2.0 - Lightweight GitHub OTA Patch DEX Builder (~100 KB)
# Usage: .\scripts\build_ota.ps1 -PatchVersion 201 -PatchName "2.0.1" -Notes "..."
# ==============================================================================
param(
    [int]$PatchVersion = 201,
    [string]$PatchName = "2.0.1",
    [int]$MinBaseVersionCode = 20900200,
    [string]$Notes = "GNFLNOTE OTA hot-update patch"
)

$ErrorActionPreference = "Stop"

$JdkBin     = "C:\Program Files\Android\Android Studio\jbr"
$AndroidSdk = "C:\Users\Notebook\AppData\Local\Android\Sdk"
$BuildTools = "$AndroidSdk\build-tools\36.0.0"
$AndroidJar = "$AndroidSdk\platforms\android-36.1\android.jar"

$WorkDir    = "C:\Work\GNFLNOTE-V2.0"
$ZxingJar   = "$WorkDir\libs\zxing-core.jar"
$OtaDir     = "$WorkDir\ota"
$OtaBuild   = "$WorkDir\build_out\ota_build"

$env:JAVA_HOME = $JdkBin
$env:PATH = "$JdkBin\bin;$BuildTools;$env:PATH"

if (-not (Test-Path $OtaDir)) { New-Item -ItemType Directory -Force -Path $OtaDir | Out-Null }
if (Test-Path $OtaBuild) { Remove-Item -Recurse -Force $OtaBuild }
New-Item -ItemType Directory -Force -Path "$OtaBuild\classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$OtaBuild\dex" | Out-Null

# Compile ONLY hot-updatable feature classes (exclude UpdateBootstrap.java!)
$srcFiles = @(
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\AdaptiveStrokeSmoother.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\InkShapeRecognizer.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\SmartPenRecognizer.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\SmartPenOverlayView.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\LassoOverlayView.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\ToolbarFitHelper.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\QuickColorBarManager.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\LassoCaptureManager.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\PdfCacheManager.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\PdfRecentSubmenu.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\PdfLinkQrManager.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\DietManager.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\SmartPenManager.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\PdfPicker.java",
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\UpdateBootstrap.java"
)

Write-Host "[1/3] Compiling OTA hot-update Java sources (v$PatchName / code=$PatchVersion)..."
& "$JdkBin\bin\javac.exe" -encoding UTF-8 -cp "$AndroidJar;$ZxingJar" -d "$OtaBuild\classes" $srcFiles
if ($LASTEXITCODE -ne 0) { throw "OTA javac compilation failed" }

# UpdateBootstrap은 Base APK 전용 부트스트래퍼이므로 OTA DEX에서 제외
Get-ChildItem -Path "$OtaBuild\classes\kr\hs\gnflhs\whiteboard" -Filter "UpdateBootstrap*.class" | Remove-Item -Force

Write-Host "[2/3] Packaging standalone gnfl_patch.dex..."
$otaJar = "$OtaBuild\ota_classes.jar"
& "$JdkBin\bin\jar.exe" cf $otaJar -C "$OtaBuild\classes" .
if ($LASTEXITCODE -ne 0) { throw "OTA jar packaging failed" }
& "$BuildTools\d8.bat" --min-api 26 --output "$OtaBuild\dex" $otaJar
if ($LASTEXITCODE -ne 0) { throw "OTA d8 failed" }

$dexOut = "$OtaDir\gnfl_patch.dex"
Copy-Item "$OtaBuild\dex\classes.dex" $dexOut -Force

$hash = (Get-FileHash -Path $dexOut -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $dexOut).Length
$buildDate = Get-Date -Format "yyyy-MM-dd HH:mm"

Write-Host "[3/3] Generating ota/version.json (size=$size bytes, date=$buildDate, sha256=$hash)..."
$meta = [ordered]@{
    patchVersion       = $PatchVersion
    patchName          = $PatchName
    buildDate          = $buildDate
    minBaseVersionCode = $MinBaseVersionCode
    dexUrl             = "https://raw.githubusercontent.com/devoursky-dot/gnflnote/main/ota/gnfl_patch.dex"
    sha256             = $hash
    sizeBytes          = $size
    releaseNotes       = $Notes
}
$json = $meta | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText("$OtaDir\version.json", $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "=========================================================="
Write-Host "[DONE] OTA Patch v$PatchName built!"
Write-Host "  DEX : $dexOut ($size bytes)"
Write-Host "  JSON: $OtaDir\version.json"
Write-Host "=========================================================="
