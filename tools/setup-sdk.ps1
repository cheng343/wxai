$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = 'D:\Android\sdk'
$env:ANDROID_HOME = $root
$env:ANDROID_SDK_ROOT = $root

if (-not (Test-Path "$root\cmdline-tools\latest\bin\sdkmanager.bat")) {
    Write-Host "[1/4] downloading commandline-tools..."
    New-Item -ItemType Directory -Force "$root\cmdline-tools" | Out-Null
    $zip = "$env:TEMP\cmdtools.zip"
    if (-not (Test-Path $zip)) {
        Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip
    }
    Write-Host "[2/4] extracting..."
    $tmp = "$root\cmdline-tools\_tmp"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    if (Test-Path "$root\cmdline-tools\latest") { Remove-Item "$root\cmdline-tools\latest" -Recurse -Force }
    Move-Item "$tmp\cmdline-tools" "$root\cmdline-tools\latest"
    Remove-Item $tmp -Recurse -Force
} else {
    Write-Host "[1/4] commandline-tools already present"
}

$sdk = "$root\cmdline-tools\latest\bin\sdkmanager.bat"
Write-Host "[3/4] accepting licenses..."
$yes = (1..40 | ForEach-Object { 'y' }) -join "`n"
$yes | & $sdk --sdk_root=$root --licenses 2>&1 | Select-Object -Last 3

Write-Host "[4/4] installing packages..."
& $sdk --sdk_root=$root "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>&1 | Select-Object -Last 8

Write-Host "=== installed ==="
Get-ChildItem $root | Select-Object -ExpandProperty Name
Write-Host "DONE"
