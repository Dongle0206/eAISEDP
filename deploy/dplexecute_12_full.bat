@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 一键完整部署脚本 #12
REM 日期：2026-08-13
REM 包含：停服务→拉码→编译→建库→启服务→启前端→验证
REM
REM 使用方式：
REM   1. 修改下面的配置区路径
REM   2. 直接双击运行或 cmd 里执行
REM =====================================================================

echo ═══════════════════════════════════════════════════════
echo   eAISEDP 一键完整部署
echo   %date% %time%
echo ═══════════════════════════════════════════════════════
echo.

REM ============ 配置区（按实际环境修改）============
set "PLATFORM_DIR=D:\eaiselp\platform"
set "WEB_DIR=D:\eaiselp\web"
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
set "MYSQL_CONTAINER=eaiselp-mysql"
set "MYSQL_ROOT_PWD=root"
set "GLM_API_KEY="
REM 如果要测派生功能，填上你的智谱 API Key：
REM set "GLM_API_KEY=你的智谱API密钥"
REM =================================================

REM JWT_SECRET 固定值（开发环境，生产必须改）
set "JWT_SECRET=dev-placeholder-secret-must-be-at-least-32-bytes-long-for-hs256-algorithm"

echo [配置] 后端目录: %PLATFORM_DIR%
echo [配置] 前端目录: %WEB_DIR%
echo [配置] JDK: %JAVA_HOME%
echo [配置] MySQL 容器: %MYSQL_CONTAINER%
echo.

REM ============ Step 1: 停旧服务 ============
echo [1/8] 停止旧服务...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8085 ^| findstr LISTENING') do (
    echo   停 auth PID=%%a
    taskkill /pid %%a /f >nul 2>nul
)
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do (
    echo   停 runtime PID=%%a
    taskkill /pid %%a /f >nul 2>nul
)
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do (
    echo   停前端 PID=%%a
    taskkill /pid %%a /f >nul 2>nul
)
ping -n 4 127.0.0.1 >nul
echo   √ 服务已停止
echo.

REM ============ Step 2: 拉最新代码 ============
echo [2/8] 拉取最新代码...
cd /d "%PLATFORM_DIR%"
git pull origin main 2>nul
if errorlevel 1 echo   ! 警告: 后端 git pull 有冲突

cd /d "%WEB_DIR%"
git pull origin main 2>nul
if errorlevel 1 echo   ! 警告: 前端 git pull 有冲突
echo   √ 代码已更新
echo.

REM ============ Step 3: 编译后端 ============
echo [3/8] 编译后端（需要 1-2 分钟）...
cd /d "%PLATFORM_DIR%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call mvn clean package -DskipTests -q
if errorlevel 1 (
    echo   × 编译失败！检查 JDK 版本和 Maven
    pause
    exit /b 1
)
echo   √ 编译完成
echo.

REM ============ Step 4: 重建数据库（解决所有 schema 不匹配 + 编码问题）============
echo [4/8] 重建数据库...
REM 4.1 DROP + CREATE
docker exec %MYSQL_CONTAINER% mysql -uroot -p%MYSQL_ROOT_PWD% --default-character-set=utf8mb4 -e "DROP DATABASE IF EXISTS eaiselp; CREATE DATABASE eaiselp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
if errorlevel 1 (
    echo   × 数据库重建失败！检查 MySQL 容器是否在运行
    pause
    exit /b 1
)

REM 4.2 拷贝 schema.sql 到容器
docker cp "%PLATFORM_DIR%\eaiselp-data\src\main\resources\db\schema.sql" %MYSQL_CONTAINER%:/tmp/schema.sql

REM 4.3 容器内导入（确保 UTF-8 编码）
docker exec %MYSQL_CONTAINER% sh -c "mysql -uroot -p%MYSQL_ROOT_PWD% --default-character-set=utf8mb4 eaiselp < /tmp/schema.sql"
if errorlevel 1 (
    echo   × schema 导入失败！
    pause
    exit /b 1
)

REM 4.4 验证中文数据
echo   验证中文数据...
for /f "delims=" %%i in ('docker exec %MYSQL_CONTAINER% mysql -uroot -p%MYSQL_ROOT_PWD% --default-character-set=utf8mb4 eaiselp -N -e "SELECT display_name FROM t_user WHERE username=''admin'';"') do set ADMIN_NAME=%%i
echo   admin display_name = !ADMIN_NAME!
if "!ADMIN_NAME!"=="系统管理员" (
    echo   √ 中文正常
) else (
    echo   ! 警告: 中文可能乱码，尝试修复...
    docker exec %MYSQL_CONTAINER% mysql -uroot -p%MYSQL_ROOT_PWD% --default-character-set=utf8mb4 eaiselp -e "UPDATE t_user SET display_name='系统管理员' WHERE username='admin';"
    echo   √ 已手动修复 admin 用户名
)
echo.

REM ============ Step 5: 配置前端直连后端 ============
echo [5/8] 配置前端直连后端...
cd /d "%WEB_DIR%"
> config.js echo window.EAISELP_CONFIG = {
>> config.js echo   AUTH_BASE_URL: 'http://localhost:8085',
>> config.js echo   API_BASE_URL: 'http://localhost:8081',
>> config.js echo   TOKEN_KEY: 'eaiselp_token',
>> config.js echo   LOGIN_PAGE: 'login.html',
>> config.js echo   INDEX_PAGE: 'index.html'
>> config.js echo };
echo   √ config.js 已配置直连模式
echo.

REM ============ Step 6: 启动后端 ============
echo [6/8] 启动后端服务...

REM 生成 auth 启动脚本
set "TMP_AUTH=%TEMP%\eaiselp_start_auth.bat"
> "%TMP_AUTH%" echo @echo off
>> "%TMP_AUTH%" echo chcp 65001 ^>nul
>> "%TMP_AUTH%" echo cd /d "%PLATFORM_DIR%"
>> "%TMP_AUTH%" echo set "JWT_SECRET=%JWT_SECRET%"
>> "%TMP_AUTH%" echo set "GLM_API_KEY=%GLM_API_KEY%"
>> "%TMP_AUTH%" echo set "MYSQL_HOST=127.0.0.1"
>> "%TMP_AUTH%" echo set "MYSQL_PASSWORD=%MYSQL_ROOT_PWD%"
>> "%TMP_AUTH%" echo echo === eAISEDP Auth 启动中... 端口 8085 ===
>> "%TMP_AUTH%" echo java -jar eaiselp-auth\target\eaiselp-auth.jar
>> "%TMP_AUTH%" echo echo === Auth 已停止 ===
>> "%TMP_AUTH%" echo pause ^>nul
start "eaiselp-auth" "%TMP_AUTH%"

echo   等待 auth 启动（20秒）...
ping -n 21 127.0.0.1 >nul

REM 生成 runtime 启动脚本
set "TMP_RT=%TEMP%\eaiselp_start_runtime.bat"
> "%TMP_RT%" echo @echo off
>> "%TMP_RT%" echo chcp 65001 ^>nul
>> "%TMP_RT%" echo cd /d "%PLATFORM_DIR%"
>> "%TMP_RT%" echo set "JWT_SECRET=%JWT_SECRET%"
>> "%TMP_RT%" echo set "GLM_API_KEY=%GLM_API_KEY%"
>> "%TMP_RT%" echo set "MYSQL_HOST=127.0.0.1"
>> "%TMP_RT%" echo set "MYSQL_PASSWORD=%MYSQL_ROOT_PWD%"
>> "%TMP_RT%" echo set "SYSTEM_PATH=../agents-config"
>> "%TMP_RT%" echo echo === eAISEDP Runtime 启动中... 端口 8081 ===
>> "%TMP_RT%" echo java -jar eaiselp-runtime\target\eaiselp-runtime.jar
>> "%TMP_RT%" echo echo === Runtime 已停止 ===
>> "%TMP_RT%" echo pause ^>nul
start "eaiselp-runtime" "%TMP_RT%"

echo   等待 runtime 启动（20秒）...
ping -n 21 127.0.0.1 >nul
echo   √ 后端已启动
echo.

REM ============ Step 7: 启动前端 ============
echo [7/8] 启动前端...
cd /d "%WEB_DIR%"
start "eaiselp-web" cmd /k "cd /d %WEB_DIR% && python start-web.py"
ping -n 4 127.0.0.1 >nul
echo   √ 前端已启动
echo.

REM ============ Step 8: 验证 ============
echo [8/8] 验证服务...
echo.
echo   auth 健康检查：
curl -s --connect-timeout 5 http://localhost:8085/actuator/health
echo.
echo.
echo   runtime 健康检查：
curl -s --connect-timeout 5 http://localhost:8081/actuator/health
echo.
echo.

echo ═══════════════════════════════════════════════════════
echo   部署完成！
echo ═══════════════════════════════════════════════════════
echo.
echo   浏览器访问: http://localhost:8080/login.html
echo   账号: admin / admin123
echo.
echo   验证 checklist:
echo     1. 登录成功，用户名不乱码
echo     2. Case 管理 → 新建 Case → 查看详情
echo     3. 选角色 → 填任务 → 发起派生
echo.
if "%GLM_API_KEY%"=="" (
    echo   ⚠ 注意: GLM_API_KEY 未设置，派生会报 LLM 错误！
    echo     如需派生功能，编辑本脚本填入智谱 API Key 后重新运行。
)
echo.
pause
