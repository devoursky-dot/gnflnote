param(
    [string]$ReleaseNotes = "GNFLPDF 정식 릴리즈 (0.21ms 순정 하드웨어 가속, 480p->4K 가변 렌더링, GNFLNOTE 툴바, 페이지 미리보기, 3GB LRU 캐시/PIN 잠금, GitHub 자동 업데이트)"
)

$ErrorActionPreference = "Stop"
$ROOT = "C:\Work\GNFLPDF"
$REPO_URL = "https://github.com/devoursky-dot/gnflnote.git"
$STAGE = "$env:TEMP\gnflnote_gnflpdf_stage"

Write-Host "=== [1/4] Building GNFLPDF.apk ==="
powershell -ExecutionPolicy Bypass -File "$ROOT\build.ps1"
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$apkPath = "$ROOT\GNFLPDF.apk"
if (!(Test-Path $apkPath)) { throw "GNFLPDF.apk not found" }

# Read versionCode & versionName from AndroidManifest.xml
$manifest = Get-Content "$ROOT\AndroidManifest.xml" -Raw -Encoding UTF8
$vCode = [int]([regex]::Match($manifest, 'android:versionCode="(\d+)"').Groups[1].Value)
$vName = [regex]::Match($manifest, 'android:versionName="([^"]+)"').Groups[1].Value
$sha = (Get-FileHash -Path $apkPath -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $apkPath).Length
$buildDate = (Get-Date).ToString("yyyy-MM-dd HH:mm")

Write-Host "=== [2/4] Generating ota/version.json (v$vName / code $vCode) ==="
New-Item -ItemType Directory -Force -Path "$ROOT\ota" | Out-Null
Copy-Item $apkPath "$ROOT\ota\GNFLPDF.apk" -Force

$jsonObj = [ordered]@{
    versionCode  = $vCode
    versionName  = $vName
    buildDate    = $buildDate
    apkUrl       = "https://raw.githubusercontent.com/devoursky-dot/gnflnote/main/gnflpdf/ota/GNFLPDF.apk"
    sha256       = $sha
    sizeBytes    = $size
    releaseNotes = $ReleaseNotes
}
$jsonText = $jsonObj | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText("$ROOT\ota\version.json", $jsonText, (New-Object System.Text.UTF8Encoding($false)))
Write-Host $jsonText

Write-Host "=== [3/4] Syncing gnflpdf/ to gnflnote repo ==="
if (Test-Path $STAGE) { Remove-Item -Recurse -Force $STAGE }
git clone --depth 1 $REPO_URL $STAGE
if ($LASTEXITCODE -ne 0) { throw "git clone failed" }

$dst = "$STAGE\gnflpdf"
if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
New-Item -ItemType Directory -Force -Path "$dst\src", "$dst\res", "$dst\ota" | Out-Null

Copy-Item "$ROOT\src\*" "$dst\src" -Recurse -Force
Copy-Item "$ROOT\res\*" "$dst\res" -Recurse -Force
Copy-Item "$ROOT\AndroidManifest.xml" "$dst\AndroidManifest.xml" -Force
Copy-Item "$ROOT\build.ps1" "$dst\build.ps1" -Force
Copy-Item "$ROOT\publish_ota.ps1" "$dst\publish_ota.ps1" -Force
Copy-Item "$ROOT\ota\version.json" "$dst\ota\version.json" -Force
Copy-Item "$ROOT\ota\GNFLPDF.apk" "$dst\ota\GNFLPDF.apk" -Force

Write-Host "=== [4/4] Committing and pushing to GitHub (main) ==="
Push-Location $STAGE
try {
    git add gnflpdf
    git add -f gnflpdf/ota/GNFLPDF.apk
    $status = git status --porcelain gnflpdf
    if ($status) {
        git commit -m "Release GNFLPDF v$vName (code $vCode) with GitHub OTA updater"
        git push origin main
        if ($LASTEXITCODE -ne 0) { throw "git push failed" }
        Write-Host "PUSH SUCCESS: https://github.com/devoursky-dot/gnflnote/tree/main/gnflpdf"
    } else {
        Write-Host "No changes to commit."
    }
} finally {
    Pop-Location
}
