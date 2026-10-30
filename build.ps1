# 泥地课表 — 纯命令行构建（无需 Gradle）
# 用法: powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$build = Join-Path $root 'build'
$res   = Join-Path $root 'res'
$srcOut= Join-Path $root 'java'
$libs  = Join-Path $root 'libs'

# ---------- Android SDK ----------
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $sdk)) { throw "Android SDK not found: $sdk (set ANDROID_HOME)" }

$btRoot = Join-Path $sdk 'build-tools'
$bt = Get-ChildItem $btRoot -Directory |
        Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } -Descending |
        Select-Object -First 1
if (-not $bt) { throw "build-tools not found under $btRoot" }
$bt = $bt.FullName

$platformRoot = Join-Path $sdk 'platforms'
$platform = Get-ChildItem $platformRoot -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'android.jar') } |
        Sort-Object Name -Descending | Select-Object -First 1
if (-not $platform) { throw "android.jar not found under $platformRoot" }
$jar = Join-Path $platform.FullName 'android.jar'

# ---------- JDK ----------
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $java    = Join-Path $env:JAVA_HOME 'bin\java.exe'
    $javac   = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    $jarTool = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
} else {
    $java    = (Get-Command java.exe).Source
    $javac   = (Get-Command javac.exe).Source
    $jarTool = (Get-Command jar.exe).Source
    $keytool = (Get-Command keytool.exe).Source
}
foreach ($p in @($java, $javac, $jarTool, $keytool)) {
    if (-not (Test-Path $p)) { throw "missing JDK tool: $p" }
}

# ---------- PdfBox ----------
if (-not (Test-Path (Join-Path $libs 'pdfbox-classes.jar')) -or
    -not (Test-Path (Join-Path $libs 'assets'))) {
    Write-Host "PdfBox not found, downloading..."
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\fetch-pdfbox.ps1')
    if ($LASTEXITCODE -ne 0) { throw "fetch-pdfbox failed" }
}

foreach ($p in @($bt, $jar)) { if (-not (Test-Path $p)) { throw "missing: $p" } }

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force "$build\gen" | Out-Null
New-Item -ItemType Directory -Force "$build\classes" | Out-Null
New-Item -ItemType Directory -Force "$build\dex" | Out-Null

Write-Host "[1/7] aapt2 compile"
& "$bt\aapt2.exe" compile --dir $res -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/7] aapt2 link"
& "$bt\aapt2.exe" link -o "$build\base.apk" -I $jar --manifest "$root\AndroidManifest.xml" `
    --java "$build\gen" `
    --min-sdk-version 24 --target-sdk-version 35 --version-code 1 --version-name 1.0 `
    "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/7] javac"
$sources = @()
$sources += Get-ChildItem "$srcOut" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem "$build\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -source 8 -target 8 -encoding UTF-8 -nowarn `
    -cp "$jar;$libs\pdfbox-classes.jar" -d "$build\classes" $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/7] jar"
& $jarTool cf "$build\classes.jar" -C "$build\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "[5/7] d8"
& "$bt\d8.bat" --release --min-api 24 --lib $jar --output "$build\dex" `
    "$build\classes.jar" "$libs\pdfbox-classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[6/7] package & zipalign"
Copy-Item "$build\base.apk" "$build\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$zip = [System.IO.Compression.ZipFile]::Open("$build\unsigned.apk", [System.IO.Compression.ZipArchiveMode]::Update)

$dexEntry = $zip.CreateEntry('classes.dex')
$os = $dexEntry.Open()
$is = [System.IO.File]::OpenRead("$build\dex\classes.dex")
$is.CopyTo($os)
$is.Dispose()
$os.Dispose()

$assetRoot = Join-Path $libs 'assets'
if (Test-Path $assetRoot) {
    Get-ChildItem $assetRoot -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($assetRoot.Length + 1).Replace('\', '/')
        $entry = $zip.CreateEntry("assets/$rel")
        $os2 = $entry.Open()
        $is2 = [System.IO.File]::OpenRead($_.FullName)
        $is2.CopyTo($os2)
        $is2.Dispose()
        $os2.Dispose()
    }
}
$zip.Dispose()
& "$bt\zipalign.exe" -f -p 4 "$build\unsigned.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[7/7] sign"
$ks = Join-Path $root 'keystore\debug.keystore'
if (-not (Test-Path (Split-Path $ks))) { New-Item -ItemType Directory -Force (Split-Path $ks) | Out-Null }
if (-not (Test-Path $ks)) {
    cmd /c "`"$keytool`" -genkeypair -keystore `"$ks`" -storepass android -keypass android -alias androiddebugkey -dname `"CN=Android Debug,O=Android,C=US`" -keyalg RSA -keysize 2048 -validity 10000 >nul 2>&1"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
cmd /c "`"$bt\apksigner.bat`" sign --ks `"$ks`" --ks-pass pass:android --key-pass pass:android --out `"$build\cug-timetable.apk`" `"$build\aligned.apk`" >nul 2>&1"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

$out = "$build\cug-timetable.apk"
Write-Host ""
Write-Host "OK -> $out"
Write-Host ("size = {0:N0} bytes" -f (Get-Item $out).Length)
