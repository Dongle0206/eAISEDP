@echo off
chcp 65001 >nul
setlocal

REM =====================================================================
REM eAISEDP 服务管理脚本（部署机用）
REM 用法:
REM   service.bat up       启动基础设施(MySQL+Nacos+MinIO)
REM   service.bat down     停止基础设施
REM   service.bat status   查看容器状态
REM   service.bat logs     看实时日志
REM   service.bat restart  重启基础设施
REM   service.bat runtime  启动 runtime 服务
REM   service.bat build    重新打包
REM   service.bat deploy   一键部署 (pull + build + up + runtime)
REM =====================================================================

set PLATFORM_DIR=D:\eaiselp\platform
if not exist %PLATFORM_DIR% (
    echo [ERROR] 平台目录不存在: %PLATFORM_DIR%
    echo 请先 clone 代码到该目录
    exit /b 1
)

if "%1"=="" goto help
if "%1"=="up" goto up
if "%1"=="down" goto down
if "%1"=="status" goto status
if "%1"=="logs" goto logs
if "%1"=="restart" goto restart
if "%1"=="runtime" goto runtime
if "%1"=="build" goto build
if "%1"=="deploy" goto deploy
goto help

:up
echo [INFO] 启动基础设施...
cd /d %PLATFORM_DIR%
docker compose -f docker-compose.prod.yml up -d
echo.
echo [OK] 基础设施已启动，等待健康检查...
timeout /t 30 /nobreak >nul
docker compose -f docker-compose.prod.yml ps
goto end

:down
echo [INFO] 停止基础设施...
cd /d %PLATFORM_DIR%
docker compose -f docker-compose.prod.yml down
echo [OK] 已停止
goto end

:status
echo [INFO] 容器状态...
cd /d %PLATFORM_DIR%
docker compose -f docker-compose.prod.yml ps
echo.
echo [INFO] runtime 进程...
tasklist /fi "imagename eq java.exe" 2>nul | findstr java
goto end

:logs
echo [INFO] 实时日志（Ctrl+C 退出）...
cd /d %PLATFORM_DIR%
docker compose -f docker-compose.prod.yml logs -f
goto end

:restart
call :down
call :up
goto end

:runtime
echo [INFO] 启动 runtime 服务...
cd /d %PLATFORM_DIR%
if not exist eaiselp-runtime\target\eaiselp-runtime.jar (
    echo [ERROR] runtime jar 不存在，请先 build
    exit /b 1
)
start "eaiselp-runtime" cmd /k "cd /d %PLATFORM_DIR% && java -Xms512m -Xmx1g -jar eaiselp-runtime\target\eaiselp-runtime.jar"
echo [OK] runtime 已在新窗口启动
goto end

:build
echo [INFO] 重新打包...
cd /d %PLATFORM_DIR%
call mvn clean package -DskipTests
if errorlevel 1 (
    echo [ERROR] 打包失败
    exit /b 1
)
echo [OK] 打包成功
goto end

:deploy
echo [INFO] === 一键部署 ===
echo.
echo [1/4] 拉最新代码...
cd /d %PLATFORM_DIR%
git pull
if errorlevel 1 (
    echo [WARN] git pull 失败，继续用本地代码
)
echo.
echo [2/4] 打包...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo [ERROR] 打包失败
    exit /b 1
)
echo.
echo [3/4] 启动基础设施...
docker compose -f docker-compose.prod.yml up -d
echo.
echo [4/4] 等待基础设施就绪...
timeout /t 30 /nobreak >nul
docker compose -f docker-compose.prod.yml ps
echo.
echo ============================================
echo   部署完成！
echo   API: http://localhost:8080/api/capability/overview
echo   Nacos: http://localhost:8848/nacos
echo   MinIO: http://localhost:9001
echo.
echo   启动 runtime 服务: service.bat runtime
echo ============================================
goto end

:help
echo 用法: service.bat [命令]
echo.
echo   up        启动基础设施 (MySQL+Nacos+MinIO)
echo   down      停止基础设施
echo   status    查看状态
echo   logs      看实时日志
echo   restart   重启基础设施
echo   runtime   启动 runtime 服务
echo   build     重新打包
echo   deploy    一键部署 (pull + build + up)
echo.

:end
endlocal
