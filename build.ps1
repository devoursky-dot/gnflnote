# ==============================================================================
# GNFL NOTE - V1.7 APK Build Script (Native 3x Highlighter + PDF Link & QR Tap)
# ==============================================================================
$ErrorActionPreference = "Stop"

$VersionCode = 20900170
$VersionName = "1.7"

$JdkBin     = "C:\Program Files\Android\Android Studio\jbr"
$AndroidSdk = "C:\Users\Notebook\AppData\Local\Android\Sdk"
$BuildTools = "$AndroidSdk\build-tools\36.0.0"
$Keystore   = "C:\work\PEN\release.keystore"
$AndroidJar = "$AndroidSdk\platforms\android-36.1\android.jar"

$WorkDir    = "C:\Work\GNFLNOTE-V1.7"
$AppRoot    = "$WorkDir\app_root"
$BuildOut   = "$WorkDir\build_out"
$ZxingJar   = "$WorkDir\libs\zxing-core.jar"
$FinalApk   = "C:\Work\GNFLNOTE-V1.7.apk"

$env:JAVA_HOME = $JdkBin
$env:PATH = "$JdkBin\bin;$BuildTools;$AndroidSdk\platform-tools;$env:PATH"

Write-Host "=========================================================="
Write-Host "[1/6] Preparing build directories (V$VersionName, code=$VersionCode)..."
Write-Host "=========================================================="
if (-not (Test-Path $BuildOut)) { 
    New-Item -ItemType Directory -Force -Path $BuildOut | Out-Null
}
$classesDir = "$BuildOut\classes"
$patchDexDir = "$BuildOut\patch_dex"
$mergedDexDir = "$BuildOut\merged_dex"
if (Test-Path $classesDir) { Remove-Item -Recurse -Force $classesDir }
if (Test-Path $patchDexDir) { Remove-Item -Recurse -Force $patchDexDir }
if (Test-Path $mergedDexDir) { Remove-Item -Recurse -Force $mergedDexDir }
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
New-Item -ItemType Directory -Force -Path $patchDexDir | Out-Null
New-Item -ItemType Directory -Force -Path $mergedDexDir | Out-Null

Write-Host "=========================================================="
Write-Host "[2/6] Compiling Java Sources (SmartPen + Diet + PdfPicker + Lasso + QuickColor)..."
Write-Host "=========================================================="
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
    "$WorkDir\src\kr\hs\gnflhs\whiteboard\PdfPicker.java"
)
& "$JdkBin\bin\javac.exe" -encoding UTF-8 -cp "$AndroidJar;$ZxingJar" -d $classesDir $srcFiles
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed" }

Write-Host "=========================================================="
Write-Host "[3/6] Generating DEX with d8 and merging with Base DEX..."
Write-Host "=========================================================="
$patchJar = "$BuildOut\patch_classes.jar"
if (Test-Path $patchJar) { Remove-Item -Force $patchJar }
& "$JdkBin\bin\jar.exe" cf $patchJar -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw "jar packaging of patch classes failed" }
& "$BuildTools\d8.bat" --min-api 26 --output $patchDexDir $patchJar
if ($LASTEXITCODE -ne 0) { throw "d8 patch classes compilation failed" }

$zxingDexDir = "$BuildOut\zxing_dex"
if (Test-Path $zxingDexDir) { Remove-Item -Recurse -Force $zxingDexDir }
New-Item -ItemType Directory -Force -Path $zxingDexDir | Out-Null
& "$BuildTools\d8.bat" --min-api 26 --output $zxingDexDir $ZxingJar
if ($LASTEXITCODE -ne 0) { throw "d8 zxing-core compilation failed" }

$baseDex = "$AppRoot\classes_base.dex"
$patchDex = "$patchDexDir\classes.dex"
& "$BuildTools\d8.bat" --min-api 26 --output $mergedDexDir $baseDex $patchDex
if ($LASTEXITCODE -ne 0) { throw "d8 dex merge failed" }

Write-Host "=========================================================="
Write-Host "[4/6] Applying Dynamic Method Patch and Adler32/SHA-1 Checksums..."
Write-Host "=========================================================="
$patchedDex = "$BuildOut\classes_patched.dex"
python "$WorkDir\scripts\patch_dex.py" "$mergedDexDir\classes.dex" $patchedDex
if ($LASTEXITCODE -ne 0) { throw "patch_dex.py failed" }
Copy-Item $patchedDex "$AppRoot\classes.dex" -Force
# Multi-dex overflow (classes2.dex, classes3.dex...) support when > 65536 methods
Get-ChildItem -Path $AppRoot -Filter "classes*.dex" | Where-Object { $_.Name -match '^classes\d+\.dex$' } | Remove-Item -Force -ErrorAction SilentlyContinue
$nextDexNum = 2
Get-ChildItem -Path $mergedDexDir -Filter "classes*.dex" | Where-Object { $_.Name -match '^classes\d+\.dex$' } | Sort-Object Name | ForEach-Object {
    Copy-Item $_.FullName "$AppRoot\classes$nextDexNum.dex" -Force
    Write-Host "[+] Copied multi-dex overflow: classes$nextDexNum.dex ($($_.Length) bytes)"
    $nextDexNum++
}
# Copy standalone ZXing QR decoder DEX as next sequential classesN.dex
Copy-Item "$zxingDexDir\classes.dex" "$AppRoot\classes$nextDexNum.dex" -Force
Write-Host "[+] Copied ZXing QR DEX: classes$nextDexNum.dex ($((Get-Item "$zxingDexDir\classes.dex").Length) bytes)"

Write-Host "=========================================================="
Write-Host "[5/6] Patching Manifest Version & Packaging APK..."
Write-Host "=========================================================="
python "$WorkDir\scripts\patch_version.py" "$AppRoot\AndroidManifest.xml" $VersionCode $VersionName
if ($LASTEXITCODE -ne 0) { throw "patch_version.py failed" }
python "$WorkDir\scripts\pack_apk.py" "$AppRoot" "$BuildOut\unaligned.apk"
if ($LASTEXITCODE -ne 0) { throw "pack_apk.py failed" }

Write-Host "=========================================================="
Write-Host "[6/6] Aligning & Signing APK -> $FinalApk"
Write-Host "=========================================================="
if (Test-Path "$BuildOut\aligned.apk") { Remove-Item -Force "$BuildOut\aligned.apk" }
& "$BuildTools\zipalign.exe" -f -p 4 "$BuildOut\unaligned.apk" "$BuildOut\aligned.apk"

if (Test-Path $FinalApk) { Remove-Item -Force $FinalApk }
& "$BuildTools\apksigner.bat" sign --ks $Keystore --ks-pass pass:gnflhs1234 --ks-key-alias whiteboard --key-pass pass:gnflhs1234 --out $FinalApk "$BuildOut\aligned.apk"

if (Test-Path "$WorkDir\GNFLNOTE-V1.6.apk") { Remove-Item -Force "$WorkDir\GNFLNOTE-V1.6.apk" }
Copy-Item $FinalApk "$WorkDir\GNFLNOTE-V1.7.apk" -Force

Write-Host ""
Write-Host "=========================================================="
Write-Host "[DONE] V$VersionName (code=$VersionCode) APK Build Complete: $FinalApk"
Write-Host "=========================================================="
Get-Item $FinalApk | Select-Object FullName, Length, LastWriteTime
