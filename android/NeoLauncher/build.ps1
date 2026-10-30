$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$sdk  = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt   = "$sdk\build-tools\35.0.0"
$jar  = "$sdk\platforms\android-34\android.jar"
$out  = "$root\build"

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory "$out\classes" | Out-Null

# 0) ayudante de teclas de NEO Remote (lo usa el control web del celular)
$helper = "$root\..\NeoRemote\assets\neokeys.dex"
if (Test-Path $helper) { Copy-Item $helper "$root\assets\neokeys.dex" -Force }
# optimizador que corre en la TV (lo usa la pestaña Optimizar del control web)
Copy-Item "$root\..\..\optimizer\optimizar.sh" "$root\assets\optimizar.sh" -Force

# 1) manifest -> apk base
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$root\AndroidManifest.xml" -A "$root\assets" --min-sdk-version 21 --target-sdk-version 30
if ($LASTEXITCODE) { throw "aapt2 fallo" }

# 2) java -> class
$srcs = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 8 -Xlint:-options -cp $jar -d "$out\classes" $srcs
if ($LASTEXITCODE) { throw "javac fallo" }

# 3) class -> dex
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --lib $jar --min-api 21 --output $out @classes
if ($LASTEXITCODE) { throw "d8 fallo" }

# 4) meter classes.dex al apk
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\base.apk", "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\classes.dex", "classes.dex") | Out-Null
$zip.Dispose()

# 5) alinear y firmar
$ks = "$root\debug.keystore"
if (-not (Test-Path $ks)) {
    # llave propia de cada quien (no se sube al repositorio)
    $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
    if (-not $keytool) { $keytool = "$env:JAVA_HOME\bin\keytool.exe" }
    & $keytool -genkeypair -keystore $ks -storepass android -keypass android -alias neo -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Neo" 2>$null
}
& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$root\NeoLauncher.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner fallo" }
"OK -> $root\NeoLauncher.apk  ($([math]::Round((Get-Item "$root\NeoLauncher.apk").Length/1KB,1)) KB)"


