# =====================================================================
# eAISEDP 新机环境自检脚本
# 用法：在新机上以管理员身份运行 PowerShell，跑这个脚本
#   powershell -ExecutionPolicy Bypass -File check-env.ps1
# 作用：检查所有必装工具是否齐全 + 自动补缺简单项
# =====================================================================

$ErrorActionPreference = "Continue"
Write-Host "=========================================="
Write-Host "  eAISEDP 新机环境自检"
Write-Host "=========================================="
Write-Host ""

# ---------- 1. 硬件 ----------
Write-Host "[1/10] 硬件检查..."
$cpu = Get-CimInstance Win32_Processor
$ram = Get-CimInstance Win32_ComputerSystem
$ramGB = [math]::Round($ram.TotalPhysicalMemory/1GB, 1)
$disks = Get-CimInstance Win32_LogicalDisk -Filter "DriveType=3"
Write-Host "  CPU: $($cpu.Name) ($($cpu.NumberOfCores)C/$($cpu.NumberOfLogicalProcessors)T)"
Write-Host "  RAM: $ramGB GB"
foreach ($d in $disks) {
    $freeGB = [math]::Round($d.FreeSpace/1GB, 1)
    $totalGB = [math]::Round($d.Size/1GB, 1)
    Write-Host "  Disk $($d.DeviceID): $freeGB GB free / $totalGB GB"
}
if ($ramGB -lt 15) { Write-Host "  [WARN] RAM < 16GB, 可能跑不动全栈" -ForegroundColor Yellow }
if ($cpu.VirtualizationFirmwareEnabled) {
    Write-Host "  Virtualization: ENABLED (OK)"
} else {
    Write-Host "  [FAIL] Virtualization DISABLED in BIOS!" -ForegroundColor Red
}
Write-Host ""

# ---------- 2. Java ----------
Write-Host "[2/10] Java..."
$java = Get-Command java -ErrorAction SilentlyContinue
if ($java) {
    $jv = & java -version 2>&1 | Select-Object -First 1
    Write-Host "  OK: $jv"
} else {
    Write-Host "  [MISS] Java 未安装或不在 PATH" -ForegroundColor Red
    Write-Host "  请装 JDK 17: https://adoptium.net/temurin/releases/?version=17"
}
Write-Host ""

# ---------- 3. Maven ----------
Write-Host "[3/10] Maven..."
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvn) {
    $mv = & mvn -version 2>&1 | Select-Object -First 1
    Write-Host "  OK: $mv"
} else {
    Write-Host "  [MISS] Maven 未安装" -ForegroundColor Red
    Write-Host "  请装 Maven 3.9.x: https://maven.apache.org/download.cgi"
}
Write-Host ""

# ---------- 4. Git ----------
Write-Host "[4/10] Git..."
$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) {
    $gv = & git --version
    Write-Host "  OK: $gv"
} else {
    Write-Host "  [MISS] Git 未安装" -ForegroundColor Red
    Write-Host "  请装 Git: https://git-scm.com/download/win"
}
Write-Host ""

# ---------- 5. Docker ----------
Write-Host "[5/10] Docker..."
$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($docker) {
    $dv = & docker version --format '{{.Client.Version}}' 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  Client: $dv"
        $dsv = & docker version --format '{{.Server.Version}}' 2>$null
        if ($dsv) {
            Write-Host "  Server: $dsv (OK)"
        } else {
            Write-Host "  [WARN] Docker Server 未就绪（Docker Desktop 没启动？）" -ForegroundColor Yellow
        }
    } else {
        Write-Host "  [WARN] Docker 命令存在但无法连接 daemon" -ForegroundColor Yellow
    }
} else {
    Write-Host "  [MISS] Docker 未安装" -ForegroundColor Red
    Write-Host "  请装 Docker Desktop: https://www.docker.com/products/docker-desktop/"
}
Write-Host ""

# ---------- 6. WSL ----------
Write-Host "[6/10] WSL..."
$wslStatus = & wsl --status 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "  OK: $($wslStatus | Out-String | Split-String "`n" | Select-Object -First 2)"
} else {
    Write-Host "  [WARN] WSL --status 命令不可用或返回错误" -ForegroundColor Yellow
    Write-Host "  WSL 可能是 inbox 版（Windows 内置），需要升级到 WSL2"
    Write-Host "  参考手册阶段 5"
}
Write-Host ""

# ---------- 7. 环境变量 ----------
Write-Host "[7/10] 环境变量..."
$jh = [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")
$mh = [System.Environment]::GetEnvironmentVariable("MAVEN_HOME", "Machine")
Write-Host "  JAVA_HOME (Machine): $jh"
Write-Host "  MAVEN_HOME (Machine): $mh"
if (-not $jh) { Write-Host "  [WARN] JAVA_HOME 未配" -ForegroundColor Yellow }
if (-not $mh) { Write-Host "  [WARN] MAVEN_HOME 未配" -ForegroundColor Yellow }
Write-Host ""

# ---------- 8. 网络连通性 ----------
Write-Host "[8/10] 网络连通性..."
$urls = @(
    @{Name="GitHub"; Url="https://github.com"},
    @{Name="Maven阿里云"; Url="https://maven.aliyun.com"},
    @{Name="Docker Hub"; Url="https://registry-1.docker.io"},
    @{Name="智谱AI"; Url="https://open.bigmodel.cn"}
)
foreach ($u in $urls) {
    try {
        $r = Invoke-WebRequest -Uri $u.Url -Method Head -TimeoutSec 10 -UseBasicParsing
        Write-Host "  OK   $($u.Name) ($($r.StatusCode))"
    } catch {
        Write-Host "  FAIL $($u.Name): $($_.Exception.Message)" -ForegroundColor Red
    }
}
Write-Host ""

# ---------- 9. 本机 IP ----------
Write-Host "[9/10] 本机 IP（给本机访问用）..."
$ips = Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.*" }
foreach ($ip in $ips) {
    Write-Host "  $($ip.IPAddress) (接口: $($ip.InterfaceAlias))"
}
Write-Host ""

# ---------- 10. 项目代码 ----------
Write-Host "[10/10] 项目代码..."
if (Test-Path "D:\eaiselp\platform\.git") {
    Push-Location D:\eaiselp\platform
    $commit = & git log --oneline -1 2>&1
    Write-Host "  OK: 已 clone，最新 commit: $commit"
    Pop-Location
} elseif (Test-Path ".\.git") {
    $commit = & git log --oneline -1 2>&1
    Write-Host "  OK: 当前目录是项目，最新 commit: $commit"
} else {
    Write-Host "  [INFO] 项目未 clone，请运行:" -ForegroundColor Yellow
    Write-Host "    mkdir D:\eaiselp"
    Write-Host "    cd D:\eaiselp"
    Write-Host "    git clone https://github.com/Dongle0206/eAISEDP.git platform"
}
Write-Host ""

# ---------- 总结 ----------
Write-Host "=========================================="
Write-Host "  自检完成"
Write-Host "=========================================="
Write-Host ""
Write-Host "下一步："
Write-Host "  1. 修复上面所有 [MISS] 和 [FAIL]"
Write-Host "  2. 把本机 IP 告诉我（本机访问新机用）"
Write-Host "  3. 准备 GLM API Key（智谱开放平台）"
Write-Host ""
