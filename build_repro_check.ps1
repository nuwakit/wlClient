#Requires -Version 5.0
<#
构建一致性核查：核对工具链环境 → clean 构建未签名 release APK → 输出可对比的哈希清单。

用途：判定「同一份源码，不同 IDE / 不同 JDK 启动」产出的未签名 APK 是否字节一致。
两个 IDE 各跑一次（-Jdk 指向各自实际使用的 Gradle JDK），第二次带 -Baseline 指向上一次的报告。
Windows 默认禁止运行 .ps1，用 Bypass 起（只影响这一个进程，不改系统策略）：

  powershell -NoProfile -ExecutionPolicy Bypass -File .\build_repro_check.ps1 -Jdk 'C:\Program Files\Android\Android Studio2024\jbr'
  powershell -NoProfile -ExecutionPolicy Bypass -File .\build_repro_check.ps1 -Jdk 'C:\Program Files\Java\jdk-17' -Baseline app\build\repro-check.txt

报告默认写在 app\build\repro-check.txt（每次覆盖），环境段与产物段分开打印，
比对只看产物段哈希，避免 JAVA_HOME 这类「本来就该不同」的行干扰结论。
#>
param(
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Baseline = ''
)

$root = $PSScriptRoot
Set-Location $root

if (-not $Jdk) {
    Write-Host '未指定 JDK：用 -Jdk 传入，或先设好 JAVA_HOME' -ForegroundColor Red
    exit 1
}
$env:JAVA_HOME = $Jdk

function Get-Match($path, $pattern) {
    if (-not (Test-Path $path)) { return '?' }
    $m = Select-String -Path $path -Pattern $pattern | Select-Object -First 1
    if ($m -and $m.Matches.Count -gt 0 -and $m.Matches[0].Groups.Count -gt 1) {
        return $m.Matches[0].Groups[1].Value
    }
    return '?'
}

# local.properties 里的路径是 Java properties 转义写法（E\:\\android-sdk），解码后再打印，便于两边目视对比
function Get-PropPath($path, $pattern) {
    $v = Get-Match $path $pattern
    $v -replace '\\\\', '\' -replace '\\:', ':'
}

$lines = @()
$lines += '--- 环境段（两边本来就可能不同，只作记录） ---'
$lines += ('JAVA_HOME        : ' + $Jdk)
$javaVer = if (Test-Path "$Jdk\bin\java.exe") { (& "$Jdk\bin\java.exe" -version 2>&1 | Select-Object -First 1) -join '' } else { 'java.exe 不存在' }
$lines += ('java -version    : ' + $javaVer)
$lines += ('jlink.exe 存在   : ' + (Test-Path "$Jdk\bin\jlink.exe"))
$lines += ('GRADLE_USER_HOME : ' + $(if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { "$env:USERPROFILE\.gradle (默认)" }))
$lines += ('sdk.dir          : ' + (Get-PropPath "$root\local.properties" '^\s*sdk\.dir=(.+)$'))
$lines += ('AGP              : ' + (Get-Match "$root\gradle\libs.versions.toml" '^\s*agp\s*=\s*"([^"]+)"'))
$lines += ('Gradle wrapper   : ' + (Get-Match "$root\gradle\wrapper\gradle-wrapper.properties" 'gradle-([0-9.]+)-bin'))

# 构建：--info 用来抓 Gradle 实际挑中的 javac（验证 build.gradle 里的 toolchain 钉住了没有）
$gradleOut = & "$root\gradlew.bat" clean :app:assembleRelease --info --console=plain 2>&1
$tcLine = $gradleOut | Select-String 'Compiling with toolchain' | Select-Object -First 1
$lines += ('实际 javac       : ' + $(if ($tcLine) { $tcLine.Line.Trim() } else { '日志里没有 toolchain 行，检查构建是否失败' }))
$resLine = $gradleOut | Select-String 'BUILD SUCCESSFUL|BUILD FAILED' | Select-Object -First 1
$lines += ('构建结果         : ' + $(if ($resLine) { $resLine.Line.Trim() } else { '无结果（Gradle 未跑完）' }))

$apkFile = Get-ChildItem "$root\app\build\outputs\apk\release\*-unsigned.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $apkFile) {
    $lines += '产物             : 未找到未签名 APK'
    $report = "$root\app\build\repro-check.txt"
    New-Item -ItemType Directory -Force -Path (Split-Path $report) | Out-Null
    $lines | Set-Content -Encoding UTF8 $report
    $lines | ForEach-Object { Write-Host $_ }
    exit 1
}

$sha = [System.Security.Cryptography.SHA256]::Create()
function Get-Sha([byte[]]$bytes) { -join ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) }

$lines += '--- 产物段（比对只看这一段） ---'
$lines += ('APK              : ' + $apkFile.Name + ' (' + $apkFile.Length + ' bytes)')
$lines += ('APK sha256       : ' + (Get-Sha ([System.IO.File]::ReadAllBytes($apkFile.FullName))))

# 逐个关键条目单独取哈希：整包不同时，靠这几行定位是 dex、资源还是清单出的问题
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkFile.FullName)
foreach ($name in @('classes.dex', 'resources.arsc', 'AndroidManifest.xml')) {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq $name } | Select-Object -First 1
    if ($entry) {
        $ms = New-Object System.IO.MemoryStream
        $es = $entry.Open(); $es.CopyTo($ms); $es.Dispose()
        $lines += ('entry ' + $name.PadRight(19) + ': ' + (Get-Sha $ms.ToArray()) + ' (' + $ms.Length + ' bytes)')
        $ms.Dispose()
    }
    else {
        $lines += ('entry ' + $name.PadRight(19) + ': 不在包内')
    }
}
$zip.Dispose()

$report = "$root\app\build\repro-check.txt"
New-Item -ItemType Directory -Force -Path (Split-Path $report) | Out-Null
$lines | Set-Content -Encoding UTF8 $report
$lines | ForEach-Object { Write-Host $_ }
Write-Host ("`n报告已写入: $report") -ForegroundColor DarkGray

if ($Baseline) {
    if (-not (Test-Path $Baseline)) {
        Write-Host "基线文件不存在: $Baseline" -ForegroundColor Red
        exit 1
    }
    $prodFilter = { $_ -match '^(APK sha256|APK |entry )' }
    $baseProd = Get-Content $Baseline | Where-Object $prodFilter
    $currProd = $lines | Where-Object $prodFilter
    $diff = Compare-Object $baseProd $currProd
    if ($diff) {
        Write-Host "`n结论：两次构建的产物哈希不同 —— 不一致" -ForegroundColor Red
        $diff | ForEach-Object { Write-Host (('{0} {1}' -f $_.SideIndicator, $_.InputObject)) }
    }
    else {
        Write-Host "`n结论：产物哈希逐条一致 —— 两次构建可复现" -ForegroundColor Green
    }
}
