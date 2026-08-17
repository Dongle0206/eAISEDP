@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 一键部署脚本 #15——核心价值闭环四件套（#1验证 #2智能编排 #3预览 #4审批锁）
REM 日期：2026-08-14
REM
REM 本批次更新：
REM   #1 产出验证: CodeValidationService 分层验证
REM      HTML结构/JS语法(node)/Python编译/Java类名匹配/CSS括号
REM      编排完成后自动验证
REM   #2 编排智能化: team-orchestrator LLM 动态规划流水线
REM      小需求1步(只派dev) / 大需求8步，失败降级默认6步
REM      ★ 需要部署机同步 agents-config 仓库（新增 team-orchestrator.md）
REM   #3 在线预览: HTML 产出 iframe 沙箱预览
REM   #4 检查点集成: 编排跑部署(team-ops)前暂停等人工审批
REM      30分钟超时自动跳过；拒绝则跳过部署保留其他成果
REM
REM 部署机必须执行: agents-config 目录也要 git pull（否则智能编排降级）
REM =====================================================================

echo ═══════════════════════════════════════════════════════
echo   eAISEDP 部署 #15——核心价值闭环四件套
echo   %date% %time%
echo ═══════════════════════════════════════════════════════
echo.

set "PLATFORM_DIR=D:\eaiselp\platform"
set "WEB_DIR=D:\eaiselp\web"
set "AGENTS_DIR=D:\eaiselp\agents-config"
set "JAVA_HOME="

set "JWT_SECRET=dev-placeholder-secret-must-be-at-least-32-bytes-long-for-hs256-algorithm"
set "GLM_API_KEY=%GLM_API_KEY%"

REM ============ JDK17 自动探测 ============
if "%JAVA_HOME%"=="" (
    set "JDK_FOUND="
    for %%d in ("C:\Program Files\Eclipse Adoptium" "C:\Program Files\Java" "C:\Program Files\Microsoft" "C:\Program Files\Zulu" "C:\Program Files\BellSoft" "D:\jdk" "D:\Java") do (
        if exist %%d (
            for /d %%j in (%%d\jdk-17*) do (
                if exist "%%j\bin\java.exe" ( set "JAVA_HOME=%%j" & set "JDK_FOUND=1" )
            )
        )
    )
    if not defined JDK_FOUND ( echo [JDK] × 未找到 JDK17 & pause & exit /b 1 )
    echo [JDK] !JAVA_HOME!
)
echo.

echo [1/7] 停止旧服务...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8085 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
ping -n 4 127.0.0.1 >nul
echo   √

echo [2/7] 拉取最新代码（后端+前端+体系配置）...
cd /d "%PLATFORM_DIR%" & git pull origin main 2>nul
cd /d "%WEB_DIR%" & git pull origin main 2>nul
if exist "%AGENTS_DIR%" ( cd /d "%AGENTS_DIR%" & git pull origin main 2>nul & echo   √ agents-config 已更新^（智能编排必需^） ) else ( echo   ! 警告: %AGENTS_DIR% 不存在，智能编排将降级为固定6步 )

echo [3/7] 编译后端...
cd /d "%PLATFORM_DIR%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call mvn clean package -DskipTests -q
if errorlevel 1 ( echo   × 编译失败 & pause & exit /b 1 )
echo   √

echo [4/7] 启动 auth...
set "TMP_AUTH=%TEMP%\eaiselp_start_auth.bat"
> "%TMP_AUTH%" echo @echo off
>> "%TMP_AUTH%" echo chcp 65001 ^>nul
>> "%TMP_AUTH%" echo cd /d "%PLATFORM_DIR%"
>> "%TMP_AUTH%" echo set "JWT_SECRET=%JWT_SECRET%"
>> "%TMP_AUTH%" echo set "GLM_API_KEY=%GLM_API_KEY%"
>> "%TMP_AUTH%" echo set "MYSQL_HOST=127.0.0.1"
>> "%TMP_AUTH%" echo set "MYSQL_PASSWORD=root"
>> "%TMP_AUTH%" echo echo === Auth 8085 ===
>> "%TMP_AUTH%" echo java -jar eaiselp-auth\target\eaiselp-auth.jar
>> "%TMP_AUTH%" echo pause ^>nul
start "eaiselp-auth" "%TMP_AUTH%"
ping -n 21 127.0.0.1 >nul
echo   √

echo [5/7] 启动 runtime（SYSTEM_PATH 指向 agents-config）...
set "TMP_RT=%TEMP%\eaiselp_start_runtime.bat"
> "%TMP_RT%" echo @echo off
>> "%TMP_RT%" echo chcp 65001 ^>nul
>> "%TMP_RT%" echo cd /d "%PLATFORM_DIR%"
>> "%TMP_RT%" echo set "JWT_SECRET=%JWT_SECRET%"
>> "%TMP_RT%" echo set "GLM_API_KEY=%GLM_API_KEY%"
>> "%TMP_RT%" echo set "MYSQL_HOST=127.0.0.1"
>> "%TMP_RT%" echo set "MYSQL_PASSWORD=root"
>> "%TMP_RT%" echo set "SYSTEM_PATH=%AGENTS_DIR%"
>> "%TMP_RT%" echo echo === Runtime 8081 ===
>> "%TMP_RT%" echo java -jar eaiselp-runtime\target\eaiselp-runtime.jar
>> "%TMP_RT%" echo pause ^>nul
start "eaiselp-runtime" "%TMP_RT%"
ping -n 21 127.0.0.1 >nul
echo   √

echo [6/7] 启动前端...
cd /d "%WEB_DIR%"
start "eaiselp-web" cmd /k "cd /d %WEB_DIR% && python start-web.py"
ping -n 4 127.0.0.1 >nul
echo   √

echo [7/7] 验证...
echo   auth:    & curl -s --connect-timeout 5 http://localhost:8085/actuator/health
echo.
echo   runtime: & curl -s --connect-timeout 5 http://localhost:8081/actuator/health
echo   （runtime 日志应显示'加载完成: 23 角色'——含新增 team-orchestrator）
echo.
echo.
echo ═══════════════════════════════════════════════════════
echo   部署完成！验证核心价值闭环四件套：
echo ═══════════════════════════════════════════════════════
echo.
echo   1. 智能编排: Case详情页输入需求一键编排
echo      '修复按钮颜色' → 应只规划 1-2 步（LLM 智能精简）
echo      '开发信息共享网页' → 应规划 5-8 步
echo   2. 审批锁: 流水线跑到'运维(Ops)'前暂停
echo      显示'等待人工审批'→ 点'去审批'→ 检查点页确认/拒绝
echo   3. 产出验证: 工作区文件页 → 点'验证代码'→ 每文件 ✅/❌
echo   4. 在线预览: 工作区选中 .html → 点'预览网页'
echo.
pause
