@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 一键部署脚本 #14——产出验证 + 在线预览（核心价值闭环 #1+#3）
REM 日期：2026-08-14
REM
REM 本批次更新：
REM   [后端] CodeValidationService——AI 产出代码分层验证
REM     HTML: 结构校验（DOCTYPE/标签闭合/head-body完整）
REM     JS: node --check 真实语法校验（无 Node 降级括号平衡）
REM     Python: py_compile（无 Python 降级）
REM     Java: 类名匹配 + 括号平衡
REM   [后端] 编排完成后自动验证，结果写入编排状态
REM   [后端] 新 API:
REM     POST /api/runtime/workspace/{caseId}/validate — 手动触发验证
REM     GET  /api/runtime/workspace/{caseId}/preview?path=xx.html — HTML 预览
REM   [前端] workspace.html 加"验证代码"按钮 + "预览网页"按钮
REM
REM 验证能力按部署机可用工具自动升级：
REM   有 Node.js   → JS 真实语法校验
REM   有 Python    → Python 编译校验
REM   都没有       → 结构性检查（HTML结构/括号平衡）
REM =====================================================================

echo ═══════════════════════════════════════════════════════
echo   eAISEDP 部署 #14——产出验证 + 在线预览
echo   %date% %time%
echo ═══════════════════════════════════════════════════════
echo.

set "PLATFORM_DIR=D:\eaiselp\platform"
set "WEB_DIR=D:\eaiselp\web"
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
    if not defined JDK_FOUND (
        echo [JDK] × 未找到 JDK17！winget install EclipseAdoptium.Temurin.17.JDK
        pause & exit /b 1
    )
    echo [JDK] !JAVA_HOME!
)

echo.
echo [1/6] 停止旧服务...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8085 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
ping -n 4 127.0.0.1 >nul
echo   √

echo [2/6] 拉取最新代码...
cd /d "%PLATFORM_DIR%" & git pull origin main 2>nul
cd /d "%WEB_DIR%" & git pull origin main 2>nul
echo   √

echo [3/6] 编译后端...
cd /d "%PLATFORM_DIR%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call mvn clean package -DskipTests -q
if errorlevel 1 ( echo   × 编译失败 & pause & exit /b 1 )
echo   √

echo [4/6] 启动后端...
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

set "TMP_RT=%TEMP%\eaiselp_start_runtime.bat"
> "%TMP_RT%" echo @echo off
>> "%TMP_RT%" echo chcp 65001 ^>nul
>> "%TMP_RT%" echo cd /d "%PLATFORM_DIR%"
>> "%TMP_RT%" echo set "JWT_SECRET=%JWT_SECRET%"
>> "%TMP_RT%" echo set "GLM_API_KEY=%GLM_API_KEY%"
>> "%TMP_RT%" echo set "MYSQL_HOST=127.0.0.1"
>> "%TMP_RT%" echo set "MYSQL_PASSWORD=root"
>> "%TMP_RT%" echo set "SYSTEM_PATH=../agents-config"
>> "%TMP_RT%" echo echo === Runtime 8081 ===
>> "%TMP_RT%" echo java -jar eaiselp-runtime\target\eaiselp-runtime.jar
>> "%TMP_RT%" echo pause ^>nul
start "eaiselp-runtime" "%TMP_RT%"
ping -n 21 127.0.0.1 >nul
echo   √

echo [5/6] 启动前端...
cd /d "%WEB_DIR%"
start "eaiselp-web" cmd /k "cd /d %WEB_DIR% && python start-web.py"
ping -n 4 127.0.0.1 >nul
echo   √

echo [6/6] 验证...
echo   auth:    & curl -s --connect-timeout 5 http://localhost:8085/actuator/health
echo.
echo   runtime: & curl -s --connect-timeout 5 http://localhost:8081/actuator/health
echo.
echo.
echo ═══════════════════════════════════════════════════════
echo   部署完成！
echo ═══════════════════════════════════════════════════════
echo.
echo   验证本批次新功能：
echo     1. Case 详情页 → 一键编排（等待完成）
echo     2. 左侧菜单"工作区文件" → 选 Case
echo     3. 点"🔍 验证代码"→ 显示每个文件的验证结果
echo     4. 选中 .html 文件 → 点"👁 预览网页"→ iframe 看效果
echo.
echo   验证能力说明：
echo     node --version 有输出 → JS 真实语法校验
echo     python --version 有输出 → Python 编译校验
echo     都没有 → 结构性检查（HTML结构/括号平衡）
echo.
pause
