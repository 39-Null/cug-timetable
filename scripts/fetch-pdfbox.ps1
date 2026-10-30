# 下载 PdfBox-Android 并解出构建所需的 classes.jar 与 assets/
# 仅第一次构建需要联网执行；产物放在 libs/（已被 .gitignore 忽略）
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$libs = Join-Path $root 'libs'
New-Item -ItemType Directory -Force $libs | Out-Null

$version = '2.0.27.0'
$url = "https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/$version/pdfbox-android-$version.aar"
$aar = Join-Path $libs "pdfbox-android-$version.aar"

if (-not (Test-Path $aar)) {
    Write-Host "Downloading $url"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $url -OutFile $aar -UseBasicParsing
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$zip = [System.IO.Compression.ZipFile]::OpenRead($aar)
try {
    $classes = $zip.GetEntry('classes.jar')
    if (-not $classes) { throw "classes.jar not found in $aar" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile(
        $classes, (Join-Path $libs 'pdfbox-classes.jar'), $true)

    $assetRoot = Join-Path $libs 'assets'
    if (Test-Path $assetRoot) { Remove-Item $assetRoot -Recurse -Force }
    New-Item -ItemType Directory -Force $assetRoot | Out-Null

    foreach ($entry in $zip.Entries) {
        if (-not $entry.FullName.StartsWith('assets/')) { continue }
        if (-not $entry.Name) { continue }
        $rel = $entry.FullName.Substring('assets/'.Length)
        $out = Join-Path $assetRoot $rel
        New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $out, $true)
    }
} finally {
    $zip.Dispose()
}

$count = (Get-ChildItem (Join-Path $libs 'assets') -Recurse -File).Count
Write-Host "PdfBox $version ready: libs/pdfbox-classes.jar + $count asset files"
