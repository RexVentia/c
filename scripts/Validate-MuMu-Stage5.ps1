[CmdletBinding()]
param(
    [string]$VmName = "RobotSimulator-Debug",
    [string]$MuMuRoot = "C:\Program Files\Netease\MuMuPlayer-12.0",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $projectRoot "..\AndroidSdk\platform-tools\adb.exe"
$gradle = Join-Path $projectRoot "..\GradleDist\gradle-9.4.1\bin\gradle.bat"
$mumuCli = Join-Path $MuMuRoot "nx_main\mumu-cli.exe"
$apk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$runId = Get-Date -Format "yyyyMMdd-HHmmss"
$artifactDir = Join-Path $projectRoot "mumu-artifacts\stage5-$runId"
New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Get-Instances {
    $parsed = (& $mumuCli info --vmindex all | Out-String).Trim() | ConvertFrom-Json
    if ($parsed.PSObject.Properties.Name -contains "index") { return @($parsed) }
    return @($parsed.PSObject.Properties | ForEach-Object { $_.Value })
}

function Dump-Ui([string]$Name) {
    $remote = "/sdcard/$Name.xml"
    $local = Join-Path $artifactDir "$Name.xml"
    & $adb -s $script:serial shell uiautomator dump $remote | Out-Null
    & $adb -s $script:serial pull $remote $local | Out-Null
    return $local
}

function Read-Ui([string]$Name) {
    return [xml](Get-Content -LiteralPath (Dump-Ui $Name))
}

function Find-TextNode([xml]$Ui, [string]$Text) {
    return $Ui.SelectSingleNode("//node[@text='$Text']")
}

function Tap-Text([string]$Text, [int]$WaitMs = 500) {
    $ui = Read-Ui "tap"
    $node = Find-TextNode $ui $Text
    Assert-True ($null -ne $node) "Cannot find UI text: $Text"
    $match = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    Assert-True $match.Success "Cannot parse bounds for UI text: $Text"
    $x = ([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2
    $y = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
    & $adb -s $script:serial shell input tap ([int]$x) ([int]$y) | Out-Null
    Start-Sleep -Milliseconds $WaitMs
}

function Assert-UiText([xml]$Ui, [string]$Text) {
    Assert-True ($null -ne (Find-TextNode $Ui $Text)) "Expected UI text was not found: $Text"
}

function Save-Screenshot([string]$Name) {
    & $adb -s $script:serial exec-out screencap -p > (Join-Path $artifactDir "$Name.png")
}

function Reconnect-Adb([int]$TimeoutSeconds = 60) {
    & $adb disconnect $script:serial 2>$null | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Seconds 2
        & $adb connect $script:serial 2>$null | Out-Null
        $state = (& $adb -s $script:serial get-state 2>$null | Out-String).Trim()
        if ($state -eq "device") {
            $boot = (& $adb -s $script:serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
            if ($boot -eq "1") { return }
        }
    } while ((Get-Date) -lt $deadline)
    throw "ADB did not recover: $script:serial"
}

function Clear-And-Type([string]$VisibleText, [string]$NewText) {
    Tap-Text $VisibleText 100
    & $adb -s $script:serial shell input keyevent 123 | Out-Null
    1..12 | ForEach-Object { & $adb -s $script:serial shell input keyevent 67 | Out-Null }
    & $adb -s $script:serial shell input text $NewText | Out-Null
    & $adb -s $script:serial shell input keyevent 111 | Out-Null
}

if (-not $SkipBuild) {
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    $env:GRADLE_USER_HOME = Join-Path $projectRoot "..\.gradle-cache\RobotSimulator"
    & $gradle --offline --no-daemon --no-configuration-cache lintDebug testDebugUnitTest assembleDebug
    Assert-True ($LASTEXITCODE -eq 0) "Gradle verification failed"
}

& (Join-Path $PSScriptRoot "Deploy-MuMu.ps1") -VmName $VmName -MuMuRoot $MuMuRoot -SkipBuild
$instance = Get-Instances | Where-Object name -eq $VmName | Select-Object -First 1
Assert-True ($null -ne $instance) "Dedicated MuMu instance not found: $VmName"
$script:serial = "127.0.0.1:$($instance.adb_port)"
$main = Get-Instances | Where-Object is_main | Select-Object -First 1
Assert-True (-not $main.is_process_started) "The main MuMu instance must remain stopped"
Assert-True ((& $adb -s $script:serial shell getprop ro.product.cpu.abi).Trim() -eq "x86_64") "Unexpected ABI"
Assert-True ([int]((& $adb -s $script:serial shell getprop ro.build.version.sdk).Trim()) -ge 31) "Android API is below 31"

# Upgrade install must keep the existing Room database and routes.
& $adb -s $script:serial install -r $apk | Out-Null
Assert-True ($LASTEXITCODE -eq 0) "Upgrade installation failed"
& $adb -s $script:serial shell am force-stop com.robot.simulator | Out-Null
& $adb -s $script:serial shell monkey -p com.robot.simulator -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 2
$routesUi = Read-Ui "routes-after-upgrade"
Assert-UiText $routesUi "路线库"
Save-Screenshot "01-routes-after-upgrade"
$mapEntryUi = Read-Ui "before-map"
if ($null -eq (Find-TextNode $mapEntryUi "新建路线")) {
    Tap-Text "路线" 800
    $routesUi = Read-Ui "routes-before-map"
    Assert-UiText $routesUi "新建路线"
}

# Load the real map, draw a short route once, save it, and verify persistence.
if ($null -eq (Find-TextNode $routesUi "Stage5-Auto")) {
    & $adb -s $script:serial logcat -c
    Tap-Text "新建路线" 2500
    $editorUi = Read-Ui "map-loaded"
    if ($null -ne (Find-TextNode $editorUi "同意并继续")) {
        Tap-Text "同意并继续" 2500
        $editorUi = Read-Ui "map-loaded-after-consent"
    }
    Assert-UiText $editorUi "可缩放和移动地图"
    Save-Screenshot "02-map-loaded"
    Tap-Text "路线名称" 100
    & $adb -s $script:serial shell input text Stage5-Auto | Out-Null
    & $adb -s $script:serial shell input keyevent 111 | Out-Null
    Tap-Text "开始绘制" 1500
    $drawingUi = Read-Ui "drawing-ready"
    Assert-UiText $drawingUi "按住并拖动鼠标，松开后结束"
    & $adb -s $script:serial shell input touchscreen swipe 700 700 720 700 800 | Out-Null
    Start-Sleep -Seconds 1
    $drawnUi = Read-Ui "route-drawn"
    $drawnLengthMatch = [regex]::Match($drawnUi.OuterXml, '路线长度：([0-9.]+) 米')
    Assert-True $drawnLengthMatch.Success "Drawn route length is missing"
    $drawnLength = [double]$drawnLengthMatch.Groups[1].Value
    Assert-True ($drawnLength -gt 5.0 -and $drawnLength -lt 100.0) "Drawn route length is outside 5–100 m: $drawnLength"
    Save-Screenshot "03-route-drawn"
    $saveNode = Find-TextNode $drawnUi "保存路线"
    $saveBounds = [regex]::Match($saveNode.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    $saveX = ([int]$saveBounds.Groups[1].Value + [int]$saveBounds.Groups[3].Value) / 2
    $saveY = ([int]$saveBounds.Groups[2].Value + [int]$saveBounds.Groups[4].Value) / 2
    & $adb -s $script:serial shell input tap ([int]$saveX) ([int]$saveY) | Out-Null
    Start-Sleep -Milliseconds 1500
} else {
    & $adb -s $script:serial logcat -c
    Tap-Text "新建路线" 2500
    $editorUi = Read-Ui "map-loaded"
    Assert-UiText $editorUi "可缩放和移动地图"
    Save-Screenshot "02-map-loaded"
    & $adb -s $script:serial shell input keyevent 4 | Out-Null
    Start-Sleep -Milliseconds 800
}

& $adb -s $script:serial shell am force-stop com.robot.simulator | Out-Null
& $adb -s $script:serial shell monkey -p com.robot.simulator -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 2
$persistedUi = Read-Ui "route-persisted"
Assert-UiText $persistedUi "Stage5-Auto"

# Configure a valid short simulation. Steps are derived from its saved distance at 2.5 m/s.
$routeNode = Find-TextNode $persistedUi "Stage5-Auto"
$lengthNode = $routeNode.ParentNode.SelectNodes("node") | Where-Object { $_.text -match '^长度 ([0-9.]+) 米$' } | Select-Object -First 1
Assert-True ($null -ne $lengthNode) "Cannot read Stage5 route length"
$lengthM = [double]([regex]::Match($lengthNode.text, '([0-9.]+)').Groups[1].Value)
$steps = [math]::Round($lengthM * 140.0 / (60.0 * 2.5))
Tap-Text "参数" 1000
Tap-Text "Stage5-Auto" 300
& $adb -s $script:serial shell input swipe 1200 1100 1200 450 500 | Out-Null
Start-Sleep -Milliseconds 500
$setupUi = Read-Ui "setup"
$stepsField = Find-TextNode $setupUi "总步数"
Assert-True ($null -ne $stepsField) "Total steps field is missing"
$match = [regex]::Match($stepsField.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
$fieldX = ([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2
$fieldY = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
& $adb -s $script:serial shell input tap ([int]$fieldX) ([int]$fieldY) | Out-Null
& $adb -s $script:serial shell input text 1 | Out-Null
& $adb -s $script:serial shell input keyevent 111 | Out-Null
Start-Sleep -Milliseconds 400
$invalidSpeedUi = Read-Ui "invalid-speed"
Assert-True ($invalidSpeedUi.OuterXml -match '不在 2–3 m/s；建议步数') "Invalid speed guidance was not shown"
Clear-And-Type "140" "119"
$invalidCadenceUi = Read-Ui "invalid-cadence"
Assert-UiText $invalidCadenceUi "步频必须在 120–151 步/分钟"
Clear-And-Type "119" "140"
Clear-And-Type "1" ([string][int]$steps)
& $adb -s $script:serial shell input swipe 1200 1100 1200 500 500 | Out-Null
Start-Sleep -Seconds 1
$validUi = Read-Ui "setup-valid"
Assert-True ($validUi.OuterXml -match '平均速度：2\.[0-9]+ m/s') "Valid speed was not calculated"
Assert-UiText $validUi "开始模拟"
Tap-Text "开始模拟" 800
Save-Screenshot "04-running-with-sensors"

# Pause must freeze both position and steps; resume must advance; stop must create history.
& $adb -s $script:serial shell input swipe 2100 1050 2100 700 500 | Out-Null
Start-Sleep -Milliseconds 500
Tap-Text "暂停" 700
$pausedA = (Read-Ui "paused-a").OuterXml
Start-Sleep -Seconds 2
$pausedB = (Read-Ui "paused-b").OuterXml
$pausedStepA = [regex]::Match($pausedA, '步数 (\d+) /').Groups[1].Value
$pausedStepB = [regex]::Match($pausedB, '步数 (\d+) /').Groups[1].Value
Assert-True ($pausedStepA -eq $pausedStepB) "Step count changed while paused"
Tap-Text "继续" 500
$resumedUi = Read-Ui "resumed"
$resumed = $resumedUi.OuterXml
$resumedStep = [regex]::Match($resumed, '步数 (\d+) /').Groups[1].Value
Assert-True ([int]$resumedStep -gt [int]$pausedStepB) "Step count did not advance after resume"
$stopNode = Find-TextNode $resumedUi "提前停止"
Assert-True ($null -ne $stopNode) "Stop button is missing after resume"
$stopBounds = [regex]::Match($stopNode.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
$stopX = ([int]$stopBounds.Groups[1].Value + [int]$stopBounds.Groups[3].Value) / 2
$stopY = ([int]$stopBounds.Groups[2].Value + [int]$stopBounds.Groups[4].Value) / 2
& $adb -s $script:serial shell input tap ([int]$stopX) ([int]$stopY) | Out-Null
Start-Sleep -Milliseconds 700
& $adb -s $script:serial shell input keyevent 4 | Out-Null
Start-Sleep -Milliseconds 500
Tap-Text "历史" 1000
$historyUi = Read-Ui "history"
Assert-UiText $historyUi "Stage5-Auto"
Assert-True ($historyUi.OuterXml -match '已中止') "Stopped run was not recorded"
Save-Screenshot "05-history"

# Background/process restart and a MuMu offline interval must not lose stored records.
& $adb -s $script:serial shell input keyevent 3 | Out-Null
Start-Sleep -Seconds 1
& $adb -s $script:serial shell monkey -p com.robot.simulator -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 1
Save-Screenshot "06-before-offline"
& $adb -s $script:serial shell svc wifi disable | Out-Null
Start-Sleep -Seconds 3
# MuMu ties its TCP ADB bridge to the guest network service, so restart through the host CLI.
& $mumuCli control --vmindex $instance.index restart | Out-Null
$restartDeadline = (Get-Date).AddSeconds(90)
do {
    Start-Sleep -Seconds 3
    $restarted = Get-Instances | Where-Object index -eq $instance.index | Select-Object -First 1
} while ($restarted.player_state -ne "start_finished" -and (Get-Date) -lt $restartDeadline)
Assert-True ($restarted.player_state -eq "start_finished") "MuMu did not recover after offline test"
Reconnect-Adb 60
& $adb -s $script:serial shell svc wifi enable 2>$null | Out-Null
Start-Sleep -Seconds 2
Reconnect-Adb 60
& $adb -s $script:serial shell monkey -p com.robot.simulator -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 2
$offlineUi = Read-Ui "after-offline-restart"
Assert-UiText $offlineUi "路线库"
Assert-UiText $offlineUi "Stage5-Auto"
Save-Screenshot "06-after-offline-restart"

# Build and install a blank-key variant to verify the explicit configuration message, then restore normal APK.
$normalApk = Join-Path $artifactDir "RobotSimulator-normal.apk"
$blankKeyApk = Join-Path $artifactDir "RobotSimulator-blank-key.apk"
Copy-Item -LiteralPath $apk -Destination $normalApk
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:GRADLE_USER_HOME = Join-Path $projectRoot "..\.gradle-cache\RobotSimulator"
& $gradle --offline --no-daemon --no-configuration-cache "-PamapApiKey=" assembleDebug | Out-Null
Assert-True ($LASTEXITCODE -eq 0) "Blank-key APK build failed"
Copy-Item -LiteralPath $apk -Destination $blankKeyApk
& $adb -s $script:serial install -r $blankKeyApk | Out-Null
& $adb -s $script:serial shell am force-stop com.robot.simulator | Out-Null
& $adb -s $script:serial shell monkey -p com.robot.simulator -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 1
Tap-Text "新建路线" 800
$blankUi = Read-Ui "blank-key"
Assert-UiText $blankUi "缺少高德地图 Key"
Save-Screenshot "07-blank-key-message"
Copy-Item -LiteralPath $normalApk -Destination $apk -Force
& $adb -s $script:serial install -r $apk | Out-Null

& $adb -s $script:serial logcat -d -t 1500 > (Join-Path $artifactDir "logcat.txt")
$fatal = Select-String -LiteralPath (Join-Path $artifactDir "logcat.txt") -Pattern 'FATAL EXCEPTION|3dmap errorCode 555575'
Assert-True ($null -eq $fatal) "Fatal or privacy initialization error found in logcat"
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash
@(
    "Stage 5 MuMu validation: PASS"
    "Instance: $VmName (index $($instance.index))"
    "ADB: $script:serial"
    "API: $((& $adb -s $script:serial shell getprop ro.build.version.sdk).Trim())"
    "ABI: $((& $adb -s $script:serial shell getprop ro.product.cpu.abi).Trim())"
    "Route: Stage5-Auto, length $lengthM m, steps $steps, cadence 140 spm"
    "APK SHA-256: $hash"
) | Set-Content -LiteralPath (Join-Path $artifactDir "report.txt")

Write-Host "Stage 5 MuMu validation passed."
Write-Host "Report: $(Join-Path $artifactDir 'report.txt')"
