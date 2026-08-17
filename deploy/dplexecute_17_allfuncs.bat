@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 一键部署脚本 #17——全功能批次（#9能力注册表/#12流程可视化/#13MCP/#14审计用户/#15编排持久化/#31XSS修复）
REM 日期：2026-08-14
REM
REM ★ 本批次含数据库变更：新增 t_orchestration 表（编排持久化）
REM   部署脚本自动执行建表（不动已有数据，只 CREATE IF NOT EXISTS）
REM =====================================================================

echo ═══════════════════════════════════════════════════════
echo   eAISEDP 部署 #17——全功能批次
echo   %date% %time%
echo ═══════════════════════════════════════════════════════
echo.

set "PLATFORM_DIR=D:\eaiselp\platform"
set "WEB_DIR=D:\eaiselp\web"
set "AGENTS_DIR=D:\eaiselp\agents-config"
set "MYSQL_CONTAINER=eaiselp-mysql"
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

echo [1/8] 停止旧服务...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8085 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
ping -n 4 127.0.0.1 >nul
echo   √

echo [2/8] 拉取最新代码...
cd /d "%PLATFORM_DIR%" & git pull origin main 2>nul
cd /d "%WEB_DIR%" & git pull origin main 2>nul
if exist "%AGENTS_DIR%" ( cd /d "%AGENTS_DIR%" & git pull origin main 2>nul )
echo   √

echo [3/8] 数据库增量变更（t_orchestration 编排持久化表）...
docker exec %MYSQL_CONTAINER% mysql -uroot -proot --default-character-set=utf8mb4 eaiselp -e "CREATE TABLE IF NOT EXISTS t_orchestration (id BIGINT NOT NULL, tenant_id BIGINT NOT NULL DEFAULT 0, case_id VARCHAR(128) DEFAULT NULL, requirement TEXT, tier VARCHAR(16) DEFAULT 'fast', status VARCHAR(32) NOT NULL DEFAULT 'pending', current_role VARCHAR(64) DEFAULT NULL, pending_checkpoint_id BIGINT DEFAULT NULL, approval_message VARCHAR(1000) DEFAULT NULL, steps_json JSON DEFAULT NULL, validation_json JSON DEFAULT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, finished_at DATETIME DEFAULT NULL, PRIMARY KEY (id), KEY idx_orch_case (case_id), KEY idx_orch_status (status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编排任务表';"
if errorlevel 1 ( echo   ! 建表失败（可能表已存在，继续） ) else ( echo   √ )
echo.

echo [4/8] 编译后端...
cd /d "%PLATFORM_DIR%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call mvn clean package -DskipTests -q
if errorlevel 1 ( echo   × 编译失败 & pause & exit /b 1 )
echo   √

echo [5/8] 启动 auth...
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

echo [6/8] 启动 runtime...
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

echo [7/8] 启动前端...
cd /d "%WEB_DIR%"
start "eaiselp-web" cmd /k "cd /d %WEB_DIR% && python start-web.py"
ping -n 4 127.0.0.1 >nul
echo   √

echo [8/8] 验证...
echo   auth:    & curl -s --connect-timeout 5 http://localhost:8085/actuator/health
echo.
echo   runtime: & curl -s --connect-timeout 5 http://localhost:8081/actuator/health
echo.
echo.
echo ═══════════════════════════════════════════════════════
echo   部署完成！本批次新功能验证：
echo ═══════════════════════════════════════════════════════
echo.
echo   1. 左侧新菜单'能力注册表'——浏览 22 角色/26 技能
echo   2. 左侧新菜单'MCP 工具'——未配置显示降级提示
echo   3. Case 详情页——顶部生命周期进度条（6阶段可视化）
echo   4. 审计日志页——用户ID筛选框
echo   5. 编排重启恢复——编排中重启服务，进度可查（标记failed）
echo.
pause
