[CmdletBinding()]
param(
    [string]$VmName = "RobotSimulator-Debug",
    [string]$MuMuRoot = "C:\Program Files\Netease\MuMuPlayer-12.0",
    [switch]$SkipBuild,
    [int]$BootTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$mumuCli = Join-Path $MuMuRoot "nx_main\mumu-cli.exe"
$adb = Join-Path $projectRoot "..\AndroidSdk\platform-tools\adb.exe"
$apk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$artifactDir = Join-Path $projectRoot "mumu-artifacts"

function Get-MuMuInstances {
    $raw = (& $mumuCli info --vmindex all | Out-String).Trim()
    if (-not $raw) { return @() }
    $parsed = $raw | ConvertFrom-Json
    if ($parsed.PSObject.Properties.Name -contains "index") { return @($parsed) }
    return @($parsed.PSObject.Properties | ForEach-Object { $_.Value })
}

function Get-AdbPort([string]$vmIndex) {
    $vmFile = Join-Path $MuMuRoot "vms\MuMuPlayer-12.0-$vmIndex\MuMuPlayer-12.0-$vmIndex.nemu"
    if (-not (Test-Path -LiteralPath $vmFile)) {
        throw "Cannot find MuMu VM configuration: $vmFile"
    }
    $content = Get-Content -LiteralPath $vmFile -Raw
    $match = [regex]::Match(
        $content,
        '<Forwarding\s+name="ADB_PORT"[^>]*hostport="(?<port>\d+)"',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    )
    if (-not $match.Success) { throw "Cannot resolve the ADB port for MuMu instance $vmIndex" }
    return [int]$match.Groups["port"].Value
}

if (-not (Test-Path -LiteralPath $mumuCli)) { throw "MuMu CLI not found: $mumuCli" }
if (-not (Test-Path -LiteralPath $adb)) { throw "ADB not found: $adb" }

$instance = Get-MuMuInstances | Where-Object { $_.name -eq $VmName } | Select-Object -First 1
if (-not $instance) { throw "MuMu instance '$VmName' does not exist. Create the dedicated clone first." }
$vmIndex = [string]$instance.index

if (-not $SkipBuild) {
    $studioJbr = "C:\Program Files\Android\Android Studio\jbr"
    if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath $studioJbr)) { $env:JAVA_HOME = $studioJbr }
    $env:GRADLE_USER_HOME = Join-Path $projectRoot "..\.gradle-cache\RobotSimulator"
    $localGradle = Join-Path $projectRoot "..\GradleDist\gradle-9.4.1\bin\gradle.bat"
    if (Test-Path -LiteralPath $localGradle) {
        & $localGradle --offline --no-daemon --no-configuration-cache testDebugUnitTest assembleDebug
    } else {
        & (Join-Path $projectRoot "gradlew.bat") --no-daemon testDebugUnitTest assembleDebug
    }
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
}

if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK not found: $apk" }

$port = Get-AdbPort $vmIndex
$serial = "127.0.0.1:$port"
& $mumuCli control --vmindex $vmIndex launch | Out-Host
$deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
do {
    Start-Sleep -Seconds 2
    $current = Get-MuMuInstances | Where-Object { [string]$_.index -eq $vmIndex } | Select-Object -First 1
    if ($current.launch_err_code -and [int]$current.launch_err_code -ne 0) {
        throw "MuMu launch failed: $($current.launch_err_msg) ($($current.launch_err_code))"
    }
    & $adb connect $serial | Out-Null
    $bootCompleted = (& $adb -s $serial shell getprop sys.boot_completed 2>$null).Trim()
    if ($bootCompleted -eq "1") { break }
} while ((Get-Date) -lt $deadline)
if ($bootCompleted -ne "1") { throw "MuMu instance $vmIndex did not boot within $BootTimeoutSeconds seconds" }

& $adb connect $serial | Out-Host
if ($LASTEXITCODE -ne 0) { throw "ADB connection failed: $serial" }

$sdk = (& $adb -s $serial shell getprop ro.build.version.sdk).Trim()
$abi = (& $adb -s $serial shell getprop ro.product.cpu.abi).Trim()
if ([int]$sdk -lt 31) { throw "MuMu Android API $sdk is below the required API 31" }

& $mumuCli control --vmindex $vmIndex app install --apk $apk | Out-Host
if ($LASTEXITCODE -ne 0) { throw "APK installation failed" }
& $mumuCli control --vmindex $vmIndex app launch --package "com.robot.simulator" | Out-Host
if ($LASTEXITCODE -ne 0) { throw "Application launch failed" }

New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null
Start-Sleep -Seconds 3
& $adb -s $serial shell pm path com.robot.simulator | Set-Content -LiteralPath (Join-Path $artifactDir "package-path.txt")
& $adb -s $serial shell dumpsys activity activities | Set-Content -LiteralPath (Join-Path $artifactDir "activities.txt")
& $adb -s $serial logcat -d -t 800 | Set-Content -LiteralPath (Join-Path $artifactDir "logcat.txt")

Write-Host "RobotSimulator deployed successfully."
Write-Host "MuMu instance: $VmName (index $vmIndex)"
Write-Host "ADB serial: $serial"
Write-Host "Android API: $sdk; ABI: $abi"
Write-Host "Artifacts: $artifactDir"
