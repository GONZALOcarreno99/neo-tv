$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$sdk  = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt   = "$sdk\build-tools\35.0.0"
$jar  = "$sdk\platforms\android-34\android.jar"
$out  = "$root\build"

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\classes", "$out\helper", "$out\res", "$root\assets" | Out-Null

# 1) ayudante que corre en la TV -> assets/neokeys.dex
& javac --release 8 -Xlint:-options -nowarn -cp $jar -d "$out\helper" "$root\tvhelper\com\gonzalo\neoremote\KeyServer.java"
if ($LASTEXITCODE) { throw "javac ayudante fallo" }
& "$bt\d8.bat" --lib $jar --min-api 21 --output "$out\helper" (Get-ChildItem "$out\helper" -Recurse -Filter *.class).FullName
Copy-Item "$out\helper\classes.dex" "$root\assets\neokeys.dex" -Force
Copy-Item "$root\..\..\optimizer\optimizar.sh" "$root\assets\optimizar.sh" -Force

# 2) recursos (ícono) + manifest -> apk base
Get-ChildItem "$root\res" -Recurse -File | ForEach-Object {
    & "$bt\aapt2.exe" compile $_.FullName -o "$out\res"
    if ($LASTEXITCODE) { throw "aapt2 compile fallo" }
}
$flats = (Get-ChildItem "$out\res" -Filter *.flat).FullName
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$root\AndroidManifest.xml" -A "$root\assets" --min-sdk-version 26 --target-sdk-version 34 @flats
if ($LASTEXITCODE) { throw "aapt2 link fallo" }

# 3) código de la app
$srcs = (Get-ChildItem "$root\src" -Recurse -Filter *.java).FullName
& javac --release 8 -Xlint:-options -nowarn -cp $jar -d "$out\classes" $srcs
if ($LASTEXITCODE) { throw "javac fallo" }
& "$bt\d8.bat" --lib $jar --min-api 26 --output $out (Get-ChildItem "$out\classes" -Recurse -Filter *.class).FullName
if ($LASTEXITCODE) { throw "d8 fallo" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\base.apk", "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\classes.dex", "classes.dex") | Out-Null
$zip.Dispose()

# 4) alinear y firmar (misma llave de prueba que NEO)
$ks = "$root\..\NeoLauncher\debug.keystore"
if (-not (Test-Path $ks)) {
    # llave propia de cada quien (no se sube al repositorio)
    $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
    if (-not $keytool) { $keytool = "$env:JAVA_HOME\bin\keytool.exe" }
    & $keytool -genkeypair -keystore $ks -storepass android -keypass android -alias neo -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Neo" 2>$null
}
& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$root\NeoRemote.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner fallo" }
"OK -> $root\NeoRemote.apk  ($([math]::Round((Get-Item "$root\NeoRemote.apk").Length/1KB,1)) KB)"
