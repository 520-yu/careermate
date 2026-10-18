<#
.SYNOPSIS
    CareerMate 一键启动脚本。

.DESCRIPTION
    之前每次开发要手动开 5-6 个窗口（Ollama / Qdrant / MinIO / RocketMQ NameServer /
    Broker，再加上后端和前端）。这个脚本把「依赖组件」一次性拉起来，并把日志落到
    ops\logs\ 下，PID 记到 ops\logs\pids.json，交给 stop-all.ps1 精确停止。

    默认只起五个依赖组件，后端和前端仍然由你自己控制（IDEA / npm run dev），
    因为这两者你还要改代码、要热更新、要看报错，塞进脚本反而更难调试。

.PARAMETER WithBackend
    额外启动后端 jar（要求 target 下已有 jar）。

.PARAMETER WithFrontend
    额外启动前端 Vite dev server。

.PARAMETER All
    等价于 -WithBackend -WithFrontend。

.PARAMETER TimeoutSeconds
    每个组件等待端口就绪的秒数，默认 30。

.EXAMPLE
    .\start-all.ps1
    只起五个依赖组件。

.EXAMPLE
    .\start-all.ps1 -All
    全起。

.NOTES
    本文件必须是 UTF-8 with BOM 保存，否则 Windows PowerShell 5.1 会因中文注释解析失败。
#>
[CmdletBinding()]
param(
    [switch]$WithBackend,
    [switch]$WithFrontend,
    [switch]$All,
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = 'Stop'

if ($All) {
    $WithBackend = $true
    $WithFrontend = $true
}

# ============================ 路径与常量 ============================

$OpsDir = $PSScriptRoot
$RootDir = Split-Path $OpsDir -Parent                      # careermate
$WebDir = Join-Path (Split-Path $RootDir -Parent) 'careermate-web'
$LogDir = Join-Path $OpsDir 'logs'
$PidFile = Join-Path $LogDir 'pids.json'

New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

$script:Records = [System.Collections.Generic.List[object]]::new()
# 本次运行被跳过的组件（端口已在监听），单独记一份 —— 让最后的汇总能把
# "本次新起的" 和 "本来就在跑的" 都列出来，否则汇总只显示 2 个，像另外 3 个没起来。
$script:Skipped = [System.Collections.Generic.List[object]]::new()

# 合并上一次的记录，而不是清空。
# 教训：原来这里直接删掉 pids.json，结果第二次运行后，第一次起的 Ollama/Qdrant/MinIO
# 就失去了追踪 —— stop-all 不会停它们，而用户以为停了。这正是要避免的「假成功」。
# 正确做法：保留 PID 仍然存活的记录，丢掉已经退出的。
if (Test-Path $PidFile) {
    try {
        $previous = @([System.IO.File]::ReadAllText($PidFile, [System.Text.Encoding]::UTF8) | ConvertFrom-Json)
 
        # 兼容 Windows PowerShell 5.1 的一个行为差异：
        # 它的 ConvertFrom-Json 处理 JSON 数组时，返回的是「一个 Object[]」而不是多个元素，
        # 于是 @(...) 包出来只有 1 个元素，$prev 变成整条数组，后面的 [int]$prev.pid
        # 会直接报 "无法将 System.Object[] 转换为 System.Int32"。
        # PowerShell 7 不需要这段，但加上它对两个版本都正确。
        if ($previous.Count -eq 1 -and $previous[0] -is [System.Array]) {
            $previous = @($previous[0])
        }
    }
    catch {
        Write-Host "  [注意] 旧的 pids.json 无法解析，已忽略：$($_.Exception.Message)" -ForegroundColor Yellow
        $previous = @()
    }
    foreach ($prev in $previous) {
        if ($prev.pid -and (Get-Process -Id ([int]$prev.pid) -ErrorAction SilentlyContinue)) {
            $script:Records.Add($prev)
        }
    }
    if ($script:Records.Count -gt 0) {
        Write-Host "  接续上次记录：$($script:Records.Count) 个进程仍在运行" -ForegroundColor DarkGray
    }
}

# ============================ 工具函数 ============================

function Write-Step([string]$Text) {
    Write-Host ""
    Write-Host "== $Text" -ForegroundColor Cyan
}

function Write-Ok([string]$Text) {
    Write-Host "  [OK]   $Text" -ForegroundColor Green
}

function Write-Skip([string]$Text) {
    Write-Host "  [跳过] $Text" -ForegroundColor DarkGray
}

function Write-Fail([string]$Text) {
    Write-Host "  [失败] $Text" -ForegroundColor Red
}

<#
    判断端口是否已在监听。

    为什么不用 Test-NetConnection：它每次要几百毫秒，8 个组件轮询时会明显拖慢启动。
    这里直接用 TcpClient 带超时连接，快一个数量级。
#>
function Test-Port([int]$Port, [int]$TimeoutMs = 300) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        return ($task.Wait($TimeoutMs) -and $client.Connected)
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

<# 轮询等待端口就绪，返回 $true/$false。 #>
function Wait-Port([int]$Port, [int]$Timeout) {
    $deadline = (Get-Date).AddSeconds($Timeout)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) { return $true }
        Start-Sleep -Milliseconds 400
    }
    return (Test-Port $Port)
}

<#
    取某个进程的整棵子进程树。

    为什么需要它：`cmd /c mqnamesrv.cmd` 和 `cmd /c npm run dev` 都只是外壳，
    真正占端口的是它们派生出来的 java / node。stop-all 如果只杀外壳，会留下孤儿进程，
    端口一直不释放 —— 这个坑在本项目里已经真实遇到过（Vite 的 node 进程在 PowerShell
    外壳被杀后仍然活着）。所以在启动成功后立刻把树记下来。
#>
function Get-ProcessTree([int]$RootPid) {
    $all = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Select-Object ProcessId, ParentProcessId
    $ids = [System.Collections.Generic.List[int]]::new()
    $queue = [System.Collections.Generic.Queue[int]]::new()
    $queue.Enqueue($RootPid)
    while ($queue.Count -gt 0) {
        $current = $queue.Dequeue()
        foreach ($child in ($all | Where-Object { $_.ParentProcessId -eq $current })) {
            $childId = [int]$child.ProcessId
            $ids.Add($childId)
            $queue.Enqueue($childId)
        }
    }
    $arr = $ids.ToArray()
    if ($arr.Count -eq 0) { return @() }
    return , $arr
}

<# 失败时把该组件日志的尾部打出来 —— 否则用户只知道"失败了"，不知道原因。 #>
function Show-LogTail([string]$Name, [int]$Lines = 15) {
    foreach ($kind in @('err', 'out')) {
        $f = Join-Path $LogDir "$Name.$kind.log"
        if ((Test-Path $f) -and (Get-Item $f).Length -gt 0) {
            Write-Host "  --- $Name.$kind.log 尾部 ---" -ForegroundColor Yellow
            Get-Content $f -Tail $Lines | ForEach-Object { Write-Host "  | $_" -ForegroundColor DarkYellow }
        }
    }
}

<# 把启动记录落盘。增量写入，保证中途失败时也已启动的组件能被 stop-all 找到。 #>
function Save-Records {
    $json = $script:Records | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText($PidFile, $json, [System.Text.UTF8Encoding]::new($false))
}

<#
    启动一个组件。

    - 端口已在监听 → 跳过（幂等，重复执行脚本不会起两遍）
    - 可执行文件不存在 → 报错并返回 $null
    - 返回启动记录对象，失败返回 $null
#>
function Start-Component {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Desc,
        [Parameter(Mandatory)][string]$Exe,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory = $null,
        [Parameter(Mandatory)][int]$Port,
        # StopMatch：停止时用来认领进程的命令行正则。
        # 因为 .cmd / npm 会派生子进程，只杀父进程会留下孤儿（vite 的 node 尤其常见）。
        [string]$StopMatch = $null
    )

    if (Test-Port $Port) {
        Write-Skip "$Desc（端口 $Port 已在监听）"
        $script:Skipped.Add([pscustomobject]@{ desc = $Desc; port = $Port })
        return $null
    }

    # 解析可执行文件。
    # 注意：Test-Path 只认文件路径，**不查 PATH** —— 裸命令名（cmd.exe / java.exe）
    # 用它判断会误报“找不到”，必须再用 Get-Command 在 PATH 里找一次。
    $resolvedExe = $Exe
    if (-not (Test-Path $Exe)) {
        $cmdInfo = Get-Command $Exe -ErrorAction SilentlyContinue
        if ($cmdInfo -and $cmdInfo.Source) {
            $resolvedExe = $cmdInfo.Source
        }
        else {
            Write-Fail "$Desc：找不到可执行文件或命令「$Exe」"
            throw "$Desc 启动失败：可执行文件不存在（$Exe）"
        }
    }

    $stdOut = Join-Path $LogDir "$Name.out.log"
    $stdErr = Join-Path $LogDir "$Name.err.log"

    $spArgs = @{
        FilePath               = $resolvedExe
        RedirectStandardOutput = $stdOut
        RedirectStandardError  = $stdErr
        WindowStyle            = 'Hidden'
        PassThru               = $true
    }
    if ($Arguments.Count -gt 0) { $spArgs.ArgumentList = $Arguments }
    if ($WorkingDirectory) { $spArgs.WorkingDirectory = $WorkingDirectory }

    try {
        $proc = Start-Process @spArgs
    }
    catch {
        Write-Fail "$Desc：启动进程失败 - $($_.Exception.Message)"
        throw "$Desc 启动失败：进程无法创建。"
    }

    $record = [pscustomobject]@{
        name      = $Name
        desc      = $Desc
        pid       = $proc.Id
        port      = $Port
        stopMatch = $StopMatch
        startedAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    }
    $script:Records.Add($record)
    Save-Records

    Write-Ok ("{0} 已启动（PID {1}）" -f $Desc, $proc.Id)
    return $record
}

<# 启动后等端口就绪；失败则打印日志并中止整个脚本。 #>
function Confirm-Ready([object]$Record, [string]$Name, [string]$Desc) {
    if ($null -eq $Record) { return }   # 已被跳过或启动失败
    if (Wait-Port $Record.port $TimeoutSeconds) {
        Write-Ok ("{0} 端口 {1} 就绪" -f $Desc, $Record.port)
        # 端口就绪之后立刻把子进程树记进 pids.json，供 stop-all.ps1 精确停止。
        # 记在「就绪之后」而不是「启动之后」：外壳刚起时子进程可能还没派生出来。
        try {
            $tree = Get-ProcessTree -RootPid $Record.pid
            $Record | Add-Member -NotePropertyName children -NotePropertyValue $tree -Force
            Save-Records
        }
        catch {
            Write-Host "  [注意] 无法记录子进程树（stop-all 可能无法完全清理）：$($_.Exception.Message)" -ForegroundColor Yellow
        }
        return
    }
    Write-Fail ("{0} 在 {1} 秒内未监听端口 {2}" -f $Desc, $TimeoutSeconds, $Record.port)
    Show-LogTail $Name
    throw "$Desc 启动失败，后续组件已中止。请查看 $LogDir\$Name.err.log"
}

<#
    任何组件启动失败都会走到这里。

    为什么用 trap 而不是在每个阶段包 try/catch：trap 是脚本级兜底，不需要把启动流程
    整段包起来（那样缩进变深、可读性变差）。关键作用是失败时把「已经启动了哪些」打印出来 ——
    否则用户既不知道要不要清理，也分不清是第几步出的问题。
#>
trap {
    Write-Host ""
    Write-Host "================ 启动中止 ================" -ForegroundColor Red
    Write-Host ("  原因：{0}" -f $_.Exception.Message) -ForegroundColor Red
    Write-Host ""
    if ($script:Records -and $script:Records.Count -gt 0) {
        Write-Host "  已经启动的组件（可用 .\stop-all.ps1 清理）：" -ForegroundColor Yellow
        foreach ($rec in $script:Records) {
            Write-Host ("    {0,-28} PID {1,-8} 端口 {2}" -f $rec.desc, $rec.pid, $rec.port) -ForegroundColor Gray
        }
    }
    else {
        Write-Host "  没有任何组件被启动。" -ForegroundColor Gray
    }
    Write-Host ""
    Write-Host "  日志目录：$LogDir" -ForegroundColor Gray
    exit 1
}

# ============================ 开始 ============================

$host.UI.RawUI.WindowTitle = 'CareerMate 启动器'

Write-Host ""
Write-Host "  CareerMate 一键启动" -ForegroundColor White
Write-Host "  项目根目录：$RootDir" -ForegroundColor DarkGray
Write-Host "  日志目录  ：$LogDir" -ForegroundColor DarkGray

# ---------- 0. 从 ops\.env 读取环境变量 ----------
# 目的：省得每开一个新会话都要重新 $env:DEEPSEEK_API_KEY = '...'。
# 约定：真实的环境变量优先于 .env —— 你在命令行显式设置的应该赢。
$EnvFile = Join-Path $OpsDir '.env'
if (Test-Path $EnvFile) {
    $loaded = 0
    Get-Content $EnvFile -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#') -and $line -match '^([^=]+)=(.*)$') {
            $key = $Matches[1].Trim()
            $val = $Matches[2].Trim().Trim('"').Trim("'")
            if (-not [Environment]::GetEnvironmentVariable($key, 'Process')) {
                [Environment]::SetEnvironmentVariable($key, $val, 'Process')
                $loaded++
            }
        }
    }
    Write-Host "  已从 .env 载入 $loaded 个环境变量" -ForegroundColor DarkGray
}

# ---------- 1. 预检：这些失败不该等到组件起来才暴露 ----------

Write-Step "1/7 预检"

if ($WithBackend) {
    if (-not $env:DEEPSEEK_API_KEY) {
        Write-Fail "环境变量 DEEPSEEK_API_KEY 未设置，后端启动后会在解析占位符时直接崩溃。"
        Write-Host "        先执行：`$env:DEEPSEEK_API_KEY = 'sk-...'" -ForegroundColor Yellow
        exit 1
    }
    $jar = Get-ChildItem (Join-Path $RootDir 'target\*.jar') -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) {
        Write-Fail "没有找到后端 jar。先执行：mvn package -DskipTests"
        exit 1
    }
    Write-Ok "后端 jar：$($jar.Name)"
}

if ($WithFrontend -and -not (Test-Path (Join-Path $WebDir 'node_modules'))) {
    Write-Fail "前端依赖未安装：$WebDir\node_modules 不存在。先执行 npm install。"
    exit 1
}

$rocketmqBin = $null
if ($env:ROCKETMQ_HOME) {
    $candidate = Join-Path $env:ROCKETMQ_HOME 'bin'
    if (Test-Path (Join-Path $candidate 'mqnamesrv.cmd')) { $rocketmqBin = $candidate }
}
if (-not $rocketmqBin) {
    Write-Fail "找不到 RocketMQ：ROCKETMQ_HOME 未设置或目录下没有 mqnamesrv.cmd。"
    exit 1
}
Write-Ok "RocketMQ bin：$rocketmqBin"

# ---------- 2. MySQL / Redis：Windows 服务，不是前台进程 ----------

Write-Step "2/7 MySQL / Redis（Windows 服务）"

foreach ($svc in @(
        @{ Name = 'MySQL80'; Port = 3306; Label = 'MySQL' },
        @{ Name = 'Redis'; Port = 6379; Label = 'Redis' }
    )) {
    if (Test-Port $svc.Port) {
        Write-Ok "$($svc.Label) 已在监听 $($svc.Port)"
        continue
    }
    $service = Get-Service -Name $svc.Name -ErrorAction SilentlyContinue
    if (-not $service) {
        Write-Fail "$($svc.Label)：未找到名为 $($svc.Name) 的服务，请先手动启动。"
        exit 1
    }
    try {
        Start-Service -Name $svc.Name -ErrorAction Stop
        Write-Ok "$($svc.Label) 服务已启动"
    }
    catch {
        Write-Fail "$($svc.Label)：启动服务失败（可能需要管理员权限）- $($_.Exception.Message)"
        exit 1
    }
    if (-not (Wait-Port $svc.Port 20)) {
        Write-Fail "$($svc.Label) 服务已启动但端口 $($svc.Port) 仍未监听"
        exit 1
    }
}

# ---------- 3. 三个互不依赖的组件 ----------

Write-Step "3/7 Ollama / Qdrant / MinIO"

$r = Start-Component -Name 'ollama' -Desc 'Ollama（Embedding 模型）' `
    -Exe 'D:\Ollama\bin\ollama.exe' -Arguments @('serve') -Port 11434 -StopMatch 'ollama'
Confirm-Ready $r 'ollama' 'Ollama'

$r = Start-Component -Name 'qdrant' -Desc 'Qdrant（向量库）' `
    -Exe 'D:\Qdrant\qdrant-1.13.0\qdrant.exe' `
    -WorkingDirectory 'D:\Qdrant\qdrant-1.13.0' -Port 6333 -StopMatch 'qdrant'
Confirm-Ready $r 'qdrant' 'Qdrant'

$r = Start-Component -Name 'minio' -Desc 'MinIO（对象存储）' `
    -Exe 'D:\Minio\minio.exe' -WorkingDirectory 'D:\Minio' -Port 9090 -StopMatch 'minio' `
    -Arguments @('server', 'D:\Minio\data', '--license', 'D:\Minio\minio.license',
                 '--address', ':9090', '--console-address', ':9000')
Confirm-Ready $r 'minio' 'MinIO'

# ---------- 4. RocketMQ NameServer ----------

Write-Step "4/7 RocketMQ NameServer"

$r = Start-Component -Name 'namesrv' -Desc 'RocketMQ NameServer' `
    -Exe 'cmd.exe' -Arguments @('/c', 'mqnamesrv.cmd') `
    -WorkingDirectory $rocketmqBin -Port 9876 -StopMatch 'NamesrvStartup'
Confirm-Ready $r 'namesrv' 'NameServer'

# ---------- 5. RocketMQ Broker ----------
# 顺序强依赖：Broker 启动时要连 NameServer。上面已确认 9876 就绪，这里才安全。

Write-Step "5/7 RocketMQ Broker"

$r = Start-Component -Name 'broker' -Desc 'RocketMQ Broker' `
    -Exe 'cmd.exe' `
    -Arguments @('/c', 'mqbroker.cmd', '-n', '127.0.0.1:9876', '-c', '..\conf\broker.conf') `
    -WorkingDirectory $rocketmqBin -Port 10911 -StopMatch 'BrokerStartup'
Confirm-Ready $r 'broker' 'Broker'

# ---------- 6. 预建 topic ----------
# 消费者在应用启动时就订阅，topic 不存在会导致消费者容器启动失败。
# 这里不判断"是否已存在"（mqadmin topicList 输出解析很脆弱），直接重复执行 updateTopic ——
# 该命令对已存在的 topic 是幂等的，不会报错。

Write-Step "6/7 预建 RocketMQ topic"

$topics = @(
    'careermate-resume-vectorize',
    'careermate-interview-report',
    'health-test'
)
# 必须先切到 RocketMQ 的 bin 目录再调 mqadmin：
# mqadmin.cmd 内部用相对路径找依赖的 jar，在别的目录下调用会报「不是内部或外部命令」。
# 这里用 Push-Location 而不是拼绝对路径 —— cmd /c 处理带引号的路径很容易出岔子。
Push-Location $rocketmqBin
try {
    foreach ($t in $topics) {
        $result = & cmd.exe /c "mqadmin.cmd updateTopic -n 127.0.0.1:9876 -b 127.0.0.1:10911 -t $t 2>&1"
        if ($result -match 'success') {
            Write-Ok "topic 就绪：$t"
        }
        else {
            Write-Host "  [注意] topic $t 创建返回非预期输出：" -ForegroundColor Yellow
            $result | Select-Object -First 3 | ForEach-Object { Write-Host "  | $_" -ForegroundColor DarkYellow }
        }
    }
}
finally {
    Pop-Location
}

# ---------- 7. 可选：后端 / 前端 ----------

if ($WithBackend) {
    Write-Step "7/7 后端 jar"
    $r = Start-Component -Name 'backend' -Desc 'Spring Boot 后端' `
        -Exe 'java.exe' -Arguments @('-Dfile.encoding=UTF-8', '-jar', $jar.FullName) `
        -WorkingDirectory $RootDir -Port 8080 -StopMatch 'careermate'
    Confirm-Ready $r 'backend' '后端'

    # 端口通了不代表组件都连得上，这里真的打一次 /health
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/health' -TimeoutSec 30
        $bad = $health.PSObject.Properties | Where-Object { "$($_.Value)" -notlike 'OK*' }
        if ($bad) {
            Write-Host "  [注意] /health 有异常项：" -ForegroundColor Yellow
            $bad | ForEach-Object { Write-Host "  | $($_.Name) = $($_.Value)" -ForegroundColor DarkYellow }
        }
        else {
            Write-Ok "/health 六项全 OK"
        }
    }
    catch {
        Write-Fail "/health 请求失败：$($_.Exception.Message)"
    }
}

if ($WithFrontend) {
    Write-Host ""
    Write-Host "== 启动前端" -ForegroundColor Cyan
    $r = Start-Component -Name 'frontend' -Desc '前端 Vite dev server' `
        -Exe 'cmd.exe' -Arguments @('/c', 'npm run dev') `
        -WorkingDirectory $WebDir -Port 5173 -StopMatch 'vite'
    Confirm-Ready $r 'frontend' '前端'
}

# ---------- 汇总 ----------

Write-Host ""
Write-Host "================ 启动完成 ================" -ForegroundColor Green
if ($script:Records.Count -gt 0) {
    Write-Host "  本次新启动 / 接续仍在运行：" -ForegroundColor Gray
    foreach ($rec in $script:Records) {
        Write-Host ("    {0,-28} PID {1,-8} 端口 {2}" -f $rec.desc, $rec.pid, $rec.port) -ForegroundColor Gray
    }
}
else {
    Write-Host "  （没有记录到任何进程）" -ForegroundColor Yellow
}
# 只列出「在监听、但不在追踪记录里」的组件 —— 这些 stop-all 不会处理，必须让用户看见。
# 已经在 pids.json 里的（无论本次新起的还是接续的）都在上一组列过了，不重复列。
$trackedPorts = @($script:Records | ForEach-Object { [int]$_.port })
$untracked = @($script:Skipped | Where-Object { $trackedPorts -notcontains [int]$_.port })
if ($untracked.Count -gt 0) {
    Write-Host ""
    Write-Host "  端口已在监听、但不在本脚本追踪范围内（stop-all 不会处理它们）：" -ForegroundColor Yellow
    foreach ($s in $untracked) {
        Write-Host ("    {0,-28} 端口 {1}" -f $s.desc, $s.port) -ForegroundColor DarkGray
    }
}
Write-Host ""
Write-Host "  后端健康检查 : http://127.0.0.1:8080/health" -ForegroundColor Gray
Write-Host "  前端页面     : http://localhost:5173" -ForegroundColor Gray
Write-Host "  （注意是 localhost，Vite 只绑了 localhost，用 127.0.0.1 连不上）" -ForegroundColor DarkGray
Write-Host "  查看日志     : $LogDir" -ForegroundColor Gray
Write-Host "  停止全部     : .\stop-all.ps1" -ForegroundColor Gray
Write-Host ""
