@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ============================================
echo   eAISELP 高可用启动（2+2 实例+Nginx）
echo ============================================
echo.

cd /d D:\eaiselp\platform

REM 环境变量
if "%GLM_API_KEY%"=="" set GLM_API_KEY=你的key
set JWT_SECRET=dev-placeholder-secret-must-be-at-least-32-bytes-long-for-hs256-algorithm

REM 停旧服务（8085/8086/8081/8082）
echo [1/5] 停旧服务...
for %%p in (8085 8086 8081 8082) do (
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%%p ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
)
ping -n 3 127.0.0.1 >nul

REM 启动 auth × 2（8085 + 8086）
echo [2/5] 启动 auth × 2（8085 + 8086）...
start "eaiselp-auth-1" cmd /k "cd /d D:\eaiselp\platform && set GLM_API_KEY=!GLM_API_KEY! && set JWT_SECRET=!JWT_SECRET! && java -Dserver.port=8085 -jar eaiselp-auth\target\eaiselp-auth.jar"
ping -n 6 127.0.0.1 >nul
start "eaiselp-auth-2" cmd /k "cd /d D:\eaiselp\platform && set GLM_API_KEY=!GLM_API_KEY! && set JWT_SECRET=!JWT_SECRET! && java -Dserver.port=8086 -jar eaiselp-auth\target\eaiselp-auth.jar"
echo   [OK] auth × 2 启动中

REM 启动 runtime × 2（8081 + 8082）
echo [3/5] 启动 runtime × 2（8081 + 8082）...
ping -n 10 127.0.0.1 >nul
start "eaiselp-runtime-1" cmd /k "cd /d D:\eaiselp\platform && set GLM_API_KEY=!GLM_API_KEY! && set JWT_SECRET=!JWT_SECRET! && java -Dserver.port=8081 -jar eaiselp-runtime\target\eaiselp-runtime.jar"
ping -n 6 127.0.0.1 >nul
start "eaiselp-runtime-2" cmd /k "cd /d D:\eaiselp\platform && set GLM_API_KEY=!GLM_API_KEY! && set JWT_SECRET=!JWT_SECRET! && java -Dserver.port=8082 -jar eaiselp-runtime\target\eaiselp-runtime.jar"
echo   [OK] runtime × 2 启动中

REM 等待服务就绪
echo [4/5] 等待服务就绪（30秒）...
ping -n 31 127.0.0.1 >nul

REM 启动 Nginx
echo [5/5] 启动 Nginx 容器...
docker rm -f eaiselp-nginx 2>nul
cd D:\eaiselp\platform\deploy\nginx
docker compose -f docker-compose-nginx.yml up -d
cd /d D:\eaiselp\platform

echo.
echo ============================================
echo   高可用部署完成！
echo   Nginx 入口: http://localhost
echo   auth: 8085 + 8086（负载均衡）
echo   runtime: 8081 + 8082（负载均衡）
echo ============================================
pause
endlocal
