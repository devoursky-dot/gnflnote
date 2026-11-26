# GNFL NOTE - Build and Deployment Script
$ErrorActionPreference = "Stop"

$AndroidSdk = "C:\Users\Notebook\AppData\Local\Android\Sdk"
$BuildTools = "$AndroidSdk\build-tools\34.0.0"
if (-not (Test-Path $BuildTools)) {
    $BuildTools = "$AndroidSdk\build-tools\36.0.0"
}
$env:PATH = "$AndroidSdk\platform-tools;$BuildTools;$env:PATH"

$WorkDir = "C:\Work\GNFLNOTE"
$Scratch = "$WorkDir\build_out"
New-Item -ItemType Directory -Force -Path $Scratch | Out-Null

Write-Host "=== 1. Compiling PdfPicker.java ==="
$androidJar = "$AndroidSdk\platforms\android-34\android.jar"
if (-not (Test-Path $androidJar)) {
    $androidJar = (Get-ChildItem -Path "$AndroidSdk\platforms\android-*\android.jar" | Select-Object -Last 1).FullName
}

javac -encoding UTF-8 -cp $androidJar -d "$Scratch\classes" "$WorkDir\src\kr\hs\gnflhs\whiteboard\PdfPicker.java"

Write-Host "=== 2. Generating DEX with d8 ==="
& "$BuildTools\d8.bat" --min-api 26 --output "$Scratch\picker_dex" (Get-ChildItem -Path "$Scratch\classes" -Recurse -Filter "*.class" | Select-Object -ExpandProperty FullName)

Write-Host "=== 3. Merging with Base DEX ==="
& "$BuildTools\d8.bat" --min-api 26 --output "$Scratch\merged_dex" "$WorkDir\app_root\classes.dex" "$Scratch\picker_dex\classes.dex"

Write-Host "=== 4. Applying Method Patch and Adler32/SHA-1 Checksums ==="
python "$WorkDir\scripts\patch_dex.py" "$Scratch\merged_dex\classes.dex" "$Scratch\classes_patched.dex"

Write-Host "=== 5. Repacking APK ==="
Copy-Item "$WorkDir\app_root\*" "$Scratch\repack_root" -Recurse -Force
Copy-Item "$Scratch\classes_patched.dex" "$Scratch\repack_root\classes.dex" -Force

python -c "
import zipfile, os
src_dir = r'$Scratch\repack_root'
out_apk = r'$Scratch\unaligned.apk'
with zipfile.ZipFile(out_apk, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
    for root, dirs, files in os.walk(src_dir):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, src_dir)
            zf.write(full, rel)
"

Write-Host "=== 6. Zipalign ==="
& "$BuildTools\zipalign.exe" -f -p 4 "$Scratch\unaligned.apk" "$Scratch\aligned.apk"

Write-Host "=== 7. Signing APK ==="
$keystore = "C:\work\PEN\release.keystore"
& "$BuildTools\apksigner.bat" sign --ks $keystore --ks-pass pass:android --key-pass pass:android --out "C:\Work\GNFL_Whiteboard.apk" "$Scratch\aligned.apk"

Write-Host "=== 8. Installing to Electronic Whiteboard ==="
adb connect 192.168.1.3:5555
adb -s 192.168.1.3:5555 install -r -d "C:\Work\GNFL_Whiteboard.apk"

Write-Host "=== Complete! GNFL NOTE deployed successfully. ==="
