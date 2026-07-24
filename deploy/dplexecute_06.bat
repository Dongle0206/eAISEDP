@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 部署批次 06
REM 日期：2026-07-24
REM 内容：修复静态资源拦截 API + JWT 密钥一致性
REM 说明：runtime 的 /** 静态资源映射拦截了 /api/** 请求，改为精确匹配
REM 前置：已执行过批次 04 或 05
REM 双击即可执行
REM =====================================================================

echo ============================================
echo   批次 06：静态资源拦截修复
echo   日期：2026-07-24
echo   %date% %time%
echo ============================================
echo.

echo [1/4] git pull...
cd /d D:\eaiselp\platform
git pull origin main
if errorlevel 1 echo   [警告] git pull 失败
echo.

echo [2/4] mvn clean package...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo   [错误] 打包失败
    pause
    exit /b 1
)
echo.

echo [3/4] 重启 auth + runtime（确保 JWT_SECRET 一致）...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8085 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
ping -n 4 127.0.0.1 >nul

cd /d D:\eaiselp\platform
if "%GLM_API_KEY%"=="" set GLM_API_KEY=3f3582bb3f2243fba844dea90cd2a75b.s7J8gzxCxCvbEw1U
set JWT_SECRET=dev-placeholder-secret-must-be-at-least-32-bytes-long-for-hs256-algorithm

start "eaiselp-auth" cmd /k "cd /d D:\eaiselp\platform && set GLM_API_KEY=!GLM_API_KEY! && set JWT_SECRET=!JWT_SECRET! && java -jar eaiselp-auth\target\eaiselp-auth.jar"
echo   [OK] auth 启动中，等待 15 秒...
ping -n 16 127.0.0.1 >nul

start "eaiselp-runtime" cmd /k "cd /d D:\eaiselp\platform && set GLM_API_KEY=!GLM_API_KEY! && set JWT_SECRET=!JWT_SECRET! && java -jar eaiselp-runtime\target\eaiselp-runtime.jar"
echo   [OK] runtime 启动中
echo.

echo [4/4] 等待启动并端到端验证...
ping -n 21 127.0.0.1 >nul

echo   === 登录 ===
curl -s -X POST http://localhost:8085/api/v1/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123\"}"
echo.
echo.

echo ============================================
echo   批次 06 完成！
echo   刷新浏览器，重新登录，进 Case 管理发起派生
echo ============================================
pause
endlocal
