@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 一键部署脚本 #16——管理闭环写操作（#5角色/#6模型路由/#7配额/#8流转/#10动态角色）
REM 日期：2026-08-14
REM
REM 本批次更新：
REM   #5 角色管理写操作: 创建/编辑/删除自定义角色 + 权限矩阵设置
REM   #6 模型路由写操作: 在线切换模型/调优先级/启停（换模型不改数据库）
REM   #7 配额管理写操作: 在线调整租户月度额度
REM   #8 Case 状态流转 UI: 详情页流转按钮（与后端状态机一致，含返工）
REM   #10 动态角色下拉: 从 API 拉取角色列表（新增角色前端自动感知）
REM =====================================================================

echo ═══════════════════════════════════════════════════════
echo   eAISEDP 部署 #16——管理闭环写操作
echo   %date% %time%
echo ═══════════════════════════════════════════════════════
echo.

set "PLATFORM_DIR=D:\eaiselp\platform"
set "WEB_DIR=D:\eaiselp\web"
set "AGENTS_DIR=D:\eaiselp\agents-config"
set "JAVA_HOME="
set "JWT_SECRET=dev-placeholder-secret-must-be-at-least-32-bytes-long-for-hs256-algorithm"
set "GLM_API_KEY=%GLM_API_KEY%"

if "%JAVA_HOME%"=="" (
    set "JDK_FOUND="
    for %%d in ("C:\Program Files\Eclipse Adoptium" "C:\Program Files\Java" "C:\Program Files\Microsoft" "C:\Program Files\Zulu" "C:\Program Files\BellSoft" "D:\jdk" "D:\Java") do (
        if exist %%d ( for /d %%j in (%%d\jdk-17*) do ( if exist "%%j\bin\java.exe" ( set "JAVA_HOME=%%j" & set "JDK_FOUND=1" ) ) )
    )
    if not defined JDK_FOUND ( echo [JDK] × 未找到 JDK17 & pause & exit /b 1 )
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
if exist "%AGENTS_DIR%" ( cd /d "%AGENTS_DIR%" & git pull origin main 2>nul )
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
>> "%TMP_RT%" echo set "SYSTEM_PATH=%AGENTS_DIR%"
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
echo   部署完成！验证本批次功能：
echo ═══════════════════════════════════════════════════════
echo.
echo   1. 模型路由页 → 点'编辑'改模型名 → 保存 → 刷新确认生效
echo   2. 模型路由页 → 一键'停用/启用'
echo   3. 配额管理页 → 点'调整'改额度
echo   4. Case 详情页 → 基本信息下方有'流转'按钮
echo      （drafting→deriving→reviewing→testing→deploying→done）
echo   5. Case 详情页派生表单 → 角色下拉动态加载（非硬编码7个）
echo.
pause
