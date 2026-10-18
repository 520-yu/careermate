<#
.SYNOPSIS
    停止由 start-all.ps1 启动的所有组件。

.DESCRIPTION
    只停止 start-all.ps1 记录在 logs\pids.json 里的进程树，包括它们的子进程。

    为什么必须按记录停、而不是按进程名匹配：
    `cmd /c mqnamesrv.cmd` 和 `npm run dev` 都会派生子进程（java / node）。
    如果按名字杀（比如杀所有 java、所有 node），会误杀你在 IDEA 里起的后端，
    甚至杀到别的东西。所以这里只认两样东西：启动时记录的 PID，以及这些 PID 的子进程树。

    结束前会检查各端口是否真的释放，没释放的会列出来并给出提示，而不是假装成功。

.NOTES
    本文件必须是 UTF-8 with BOM 保存，否则 Windows PowerShell 5.1 会因中文注释解析失败。
#>
[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Continue'

$OpsDir = $PSScriptRoot
$LogDir = Join-Path $OpsDir 'logs'
$PidFile = Join-Path $LogDir 'pids.json'

Write-Host ""
Write-Host "  CareerMate 停止器" -ForegroundColor White

if (-not (Test-Path $PidFile)) {
    Write-Host "  没有找到 $PidFile" -ForegroundColor Yellow
    Write-Host "  说明本次开机后没有用 start-all.ps1 启动过东西，无需停止。" -ForegroundColor DarkGray
    Write-Host "  如果你用 IDEA / npm 手动起过，请自己关掉。" -ForegroundColor DarkGray
    Write-Host ""
    exit 0
}

try {
    $raw = [System.IO.File]::ReadAllText($PidFile, [System.Text.Encoding]::UTF8)
    # ConvertTo-Json 对只有一条记录时会输出对象而不是数组，这里统一成数组
    $records = @($raw | ConvertFrom-Json)
    # 兼容 Windows PowerShell 5.1：它处理 JSON 数组时 ConvertFrom-Json 返回的是
    # 「一个 Object[]」而不是多个元素，不展开的话 $records[0] 就是整条数组，
    # 后面 [int]$rec.pid 会报 "无法将 System.Object[] 转换为 System.Int32"。
    if ($records.Count -eq 1 -and $records[0] -is [System.Array]) {
        $records = @($records[0])
    }
}
catch {
    Write-Host "  pids.json 解析失败：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "  可以删掉它后重试：$PidFile" -ForegroundColor DarkGray
    exit 1
}

if ($records.Count -eq 0) {
    Write-Host "  pids.json 里没有记录。" -ForegroundColor Yellow
    exit 0
}

<# 结束一个 PID，返回是否真的做了终止动作。 #>
function Stop-One([int]$ProcessId, [string]$Label) {
    $proc = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $proc) {
        Write-Host ("  [已退出] {0}（PID {1}）" -f $Label, $ProcessId) -ForegroundColor DarkGray
        return $false
    }
    try {
        # 先试优雅结束，给它清理现场的机会（比如 RocketMQ 落盘）
        Stop-Process -Id $ProcessId -ErrorAction Stop
        Start-Sleep -Milliseconds 300
        $still = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
        if ($still) {
            Stop-Process -Id $ProcessId -Force -ErrorAction Stop
            Write-Host ("  [强制结束] {0}（PID {1}）" -f $Label, $ProcessId) -ForegroundColor Yellow
        }
        else {
            Write-Host ("  [已停止] {0}（PID {1}）" -f $Label, $ProcessId) -ForegroundColor Green
        }
        return $true
    }
    catch {
        Write-Host ("  [无法停止] {0}（PID {1}）：{2}" -f $Label, $ProcessId, $_.Exception.Message) -ForegroundColor Red
        return $false
    }
}

Write-Host ""
Write-Host "  按启动的逆序停止，先停依赖方、再停被依赖方。" -ForegroundColor DarkGray

# 逆序：后端/前端 → Broker → NameServer → MinIO → Qdrant → Ollama
$ordered = @($records)
[array]::Reverse($ordered)

foreach ($rec in $ordered) {
    Write-Host ""
    Write-Host ("  ── {0}" -f $rec.desc) -ForegroundColor Cyan

    # 先杀子进程树（java / node 这些真正干活、真正占端口的）
    $children = @($rec.children | Where-Object { $_ })
    if ($children.Count -gt 0) {
        # 子进程里也可能有父子关系，倒序杀更稳（后压栈的先杀）
        for ($i = $children.Count - 1; $i -ge 0; $i--) {
            Stop-One ([int]$children[$i]) '子进程' | Out-Null
        }
    }
    else {
        Write-Host "  （没有记录到子进程）" -ForegroundColor DarkGray
    }

    # 再杀外壳进程本身（cmd.exe / exe）
    Stop-One ([int]$rec.pid) $rec.name | Out-Null
}

# ---------- 校验：端口是否真的释放 ----------

Start-Sleep -Seconds 1

Write-Host ""
Write-Host "  端口释放校验：" -ForegroundColor Cyan

$stillBusy = @()
foreach ($rec in $records) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync('127.0.0.1', [int]$rec.port)
        $alive = $task.Wait(400) -and $client.Connected
    }
    catch { $alive = $false }
    finally { $client.Dispose() }

    if ($alive) {
        $stillBusy += $rec
        Write-Host ("  [仍占用] {0} 端口 {1}" -f $rec.desc, $rec.port) -ForegroundColor Red
    }
    else {
        Write-Host ("  [已释放] {0} 端口 {1}" -f $rec.desc, $rec.port) -ForegroundColor Green
    }
}

# ---------- 收尾 ----------

if ($stillBusy.Count -eq 0) {
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    Write-Host ""
    Write-Host "  全部已停止。MySQL / Redis 是 Windows 服务，未受影响。" -ForegroundColor Green
    Write-Host ""
    exit 0
}

Write-Host ""
Write-Host "  以下组件的端口仍被占用：" -ForegroundColor Yellow
foreach ($rec in $stillBusy) {
    Write-Host ("    {0}  端口 {1}" -f $rec.desc, $rec.port) -ForegroundColor Yellow
}
Write-Host ""
Write-Host "  可能原因：这个端口上的进程不是本脚本启动的（比如你在 IDEA 里手动起了后端）。" -ForegroundColor DarkGray
Write-Host "  排查命令（看清楚 PID 再决定，不要直接全杀）：" -ForegroundColor DarkGray
Write-Host "    Get-NetTCPConnection -LocalPort <端口> -State Listen | Select-Object OwningProcess" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  pids.json 已保留，修正后可以再次运行本脚本。" -ForegroundColor DarkGray
Write-Host ""
exit 1
