param([string]$Check = 'orientation')
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 or 21 first.' }
$sdkLine = Get-Content (Join-Path $PSScriptRoot '../local.properties') | Where-Object { $_.StartsWith('sdk.dir=') } | Select-Object -First 1
$sdkPath = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
$adbPath = Join-Path $sdkPath 'platform-tools/adb.exe'
$javaPath = Join-Path $env:JAVA_HOME 'bin/java.exe'
$jarPath = Join-Path $env:JAVA_HOME 'bin/jar.exe'
$projectPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $projectPath
try {
    & ./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE) { throw 'Build failed' }
    $apkPath = (Get-ChildItem app/build/outputs/apk/debug -Filter '*.apk' | Select-Object -First 1).FullName
    & $adbPath install -r $apkPath
    if ($LASTEXITCODE) { throw 'App installation failed' }
    $testApkPath = (Get-ChildItem app/build/outputs/apk/androidTest/debug -Filter '*.apk' | Select-Object -First 1).FullName
    & $adbPath install -r $testApkPath
    if ($LASTEXITCODE) { throw 'Test installation failed' }
    $result = (& $adbPath shell am instrument -w -e check $Check x.vladgba.keyboard.debug.test/x.vladgba.keyboard.core.KeyboardDeviceTests) -join "`n"
    Write-Output $result
    if ($LASTEXITCODE -or $result -notmatch "result=PASS $Check") { throw 'Device checks failed' }
} finally { Pop-Location }
