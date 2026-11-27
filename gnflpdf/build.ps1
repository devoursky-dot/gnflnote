$ErrorActionPreference = "Stop"

$ROOT = "C:\Work\GNFLPDF"
$BUILD = "$ROOT\build"
$SDK = "C:\Users\Notebook\AppData\Local\Android\Sdk"
$BT = "$SDK\build-tools\36.0.0"
$ANDROID_JAR = "$SDK\platforms\android-36.1\android.jar"
$JDK = "C:\Program Files\Android\Android Studio\jbr\bin"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$JDK;$env:PATH"
$KEYSTORE = "C:\work\PEN\release.keystore"

$AAPT2 = "$BT\aapt2.exe"
$D8 = "$BT\d8.bat"
$ZIPALIGN = "$BT\zipalign.exe"
$APKSIGNER = "$BT\apksigner.bat"
$JAVAC = "$JDK\javac.exe"

if (Test-Path $BUILD) { Remove-Item -Recurse -Force $BUILD }
New-Item -ItemType Directory -Force -Path "$BUILD\res_flat", "$BUILD\gen", "$BUILD\classes", "$BUILD\dex" | Out-Null

Write-Host "[1/5] aapt2 compile & link..."
& $AAPT2 compile --dir "$ROOT\res" -o "$BUILD\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

& $AAPT2 link -I $ANDROID_JAR --manifest "$ROOT\AndroidManifest.xml" -R "$BUILD\res.zip" --java "$BUILD\gen" -o "$BUILD\base.apk"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[2/5] javac..."
$javaFiles = @(Get-ChildItem "$ROOT\src" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
$genFiles = @(Get-ChildItem "$BUILD\gen" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
$allJava = $javaFiles + $genFiles

$jars = @(Get-ChildItem "$ROOT\libs" -Filter "*.jar" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
$cp = $ANDROID_JAR
if ($jars.Count -gt 0) { $cp = $cp + ";" + ($jars -join ";") }

& $JAVAC -source 11 -target 11 -encoding UTF-8 -classpath $cp -d "$BUILD\classes" @allJava
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[3/5] d8..."
$classFiles = @(Get-ChildItem "$BUILD\classes" -Recurse -Filter "*.class" | ForEach-Object { $_.FullName })
$d8Args = @("--lib", $ANDROID_JAR, "--min-api", "24", "--output", "$BUILD\dex") + $classFiles + $jars
& $D8 @d8Args
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[4/5] packaging APK (classes.dex + lib/armeabi-v7a/*.so)..."
python -c "
import os, zipfile

base_apk = r'$BUILD\base.apk'
out_apk = r'$BUILD\unsigned.apk'
dex_dir = r'$BUILD\dex'
lib_dir = r'$ROOT\lib'

with zipfile.ZipFile(base_apk, 'r') as zin, zipfile.ZipFile(out_apk, 'w') as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        comp = zipfile.ZIP_STORED if item.filename == 'resources.arsc' else zipfile.ZIP_DEFLATED
        zout.writestr(item, data, compress_type=comp)
    for fn in sorted(os.listdir(dex_dir)):
        if fn.endswith('.dex'):
            zout.write(os.path.join(dex_dir, fn), fn, compress_type=zipfile.ZIP_DEFLATED)
    for root, _, files in os.walk(lib_dir):
        for fn in sorted(files):
            full = os.path.join(root, fn)
            rel = 'lib/' + os.path.relpath(full, lib_dir).replace('\\\\', '/')
            zout.write(full, rel, compress_type=zipfile.ZIP_STORED)
print('Packed unsigned.apk successfully')
"
if ($LASTEXITCODE -ne 0) { throw "APK packaging failed" }

Write-Host "[5/5] zipalign & apksigner..."
& $ZIPALIGN -p -f 4 "$BUILD\unsigned.apk" "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

& $APKSIGNER sign --ks $KEYSTORE --ks-pass pass:gnflhs1234 --ks-key-alias whiteboard --key-pass pass:gnflhs1234 --out "$ROOT\GNFLPDF.apk" "$BUILD\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

$sz = [math]::Round((Get-Item "$ROOT\GNFLPDF.apk").Length / 1MB, 2)
Write-Host "BUILD SUCCESS: $ROOT\GNFLPDF.apk ($sz MB)"
