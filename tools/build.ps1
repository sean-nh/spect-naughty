<#
.SYNOPSIS
  Builds the release APK in a clean copy of the project OUTSIDE OneDrive, then copies the APK back.

.DESCRIPTION
  OneDrive sometimes turns freshly written files into "cloud placeholders", which makes Gradle fail with
  "not a regular file" or "Unable to delete directory" about one build in three. This script avoids that:
    1. copies index.html into www\ (the page that gets packaged)
    2. mirrors the project into a plain folder (default C:\dev\spectr-build), skipping node_modules, build output and .git
    3. runs "npx cap sync android" and "gradlew assembleRelease" there (node_modules and Gradle caches stay between runs)
    4. copies app-release.apk back next to index.html as Spectr-Acular.apk
  Your own files are never changed, except www\index.html and the APK.

  The release key is read from ~\.gradle\gradle.properties (SPECT_STORE_FILE etc.), as before.

.EXAMPLE
  pwsh -NoProfile -File tools\build.ps1              (the radio app)
  pwsh -NoProfile -File tools\build.ps1 -Target phone  (the phone / Android Auto app, in phone\)
  (PowerShell 7 is recommended. Windows PowerShell 5.1 also works: powershell -ExecutionPolicy Bypass -File tools\build.ps1)
#>
param(
  [string]$Mirror = 'C:\dev\spectr-build',
  [string]$Jdk = "$env:USERPROFILE\jdk21",   # a JDK 21 (default: a folder named jdk21 in your user folder). Android Studio's own Java 25 breaks this Gradle version
  [ValidateSet('radio','phone')][string]$Target = 'radio',   # radio = the car radio app (this folder's android\), phone = the phone / Android Auto app (phone\android\)
  [string]$OutName = ''
)

$ErrorActionPreference = 'Stop'
if (-not $OutName) { $OutName = if ($Target -eq 'phone') { 'Spectr-Acular-Phone.apk' } else { 'Spectr-Acular.apk' } }
$sub = if ($Target -eq 'phone') { 'phone\' } else { '' }
$src = (Resolve-Path "$PSScriptRoot\..").Path
if (-not (Test-Path "$Jdk\bin\java.exe")) { throw "JDK 21 not found at $Jdk. Pass -Jdk <folder>." }

Write-Host "1/4 Copying index.html into www ..."
Copy-Item "$src\index.html" "$src\www\index.html" -Force

Write-Host "2/4 Copying the project to $Mirror (outside OneDrive) ..."
New-Item -ItemType Directory -Force $Mirror | Out-Null
robocopy $src $Mirror /MIR /XD node_modules .git .gradle build "New folder" spec results .idea /XF *.zip local.properties /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed (exit code $LASTEXITCODE)" }
if (Test-Path "$src\android\local.properties") { foreach ($d in "android", "phone\android") { if (Test-Path "$Mirror\$d") { Copy-Item "$src\android\local.properties" "$Mirror\$d\local.properties" -Force } } }

Push-Location $Mirror
try {
  Write-Host "    target: $Target"
  if (-not (Test-Path "$Mirror\node_modules")) {
    Write-Host "    first run in this folder: installing node packages ..."
    npm ci --no-audit --no-fund | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
  }
  Write-Host "3/4 Syncing web files and building (about a minute) ..."
  if ($sub) { Push-Location "$Mirror\phone" }
  npx cap sync android | Out-Null
  $syncExit = $LASTEXITCODE
  if ($sub) { Pop-Location }
  if ($syncExit -ne 0) { throw "cap sync failed" }
  Push-Location "$Mirror\${sub}android"
  try {
    $env:JAVA_HOME = $Jdk
    # Gradle prints harmless notes on stderr; do not let PowerShell treat them as fatal errors. Success is judged from the text below.
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = & .\gradlew.bat assembleRelease 2>&1 | Out-String
    $ErrorActionPreference = $prevPref
    if ($out -notmatch 'BUILD SUCCESSFUL') {
      Write-Host $out.Substring([Math]::Max(0, $out.Length - 1500))
      throw "BUILD FAILED"
    }
  } finally { Pop-Location }
} finally { Pop-Location }

Write-Host "4/4 Copying the APK back ..."
$apk = "$Mirror\${sub}android\app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { throw "APK not found at $apk" }
Copy-Item $apk "$src\$OutName" -Force

$aapt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\aapt2.exe" -ErrorAction SilentlyContinue | Select-Object -Last 1
if ($aapt) {
  $line = & $aapt.FullName dump badging "$src\$OutName" | Select-String '^package' | ForEach-Object { $_.Line }
  Write-Host ("Built: " + $line.Substring(0, [Math]::Min(100, $line.Length)))
}
Write-Host "Done: $src\$OutName"
