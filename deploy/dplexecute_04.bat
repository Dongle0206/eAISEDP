@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 部署批次 04
REM 日期：2026-07-24
REM 内容：CORS 预检 OPTIONS 修复 + 重新打包 + 重启双服务 + 验证登录
REM 说明：修复跨域登录 pending 问题（OPTIONS 预检被拦截器拦截）
REM 前置：已执行过批次 01 或 03（数据库已初始化）
REM 双击即可执行，全程自动
REM =====================================================================

echo ============================================
echo   批次 04：CORS OPTIONS 修复热更新
echo   日期：2026-07-24
echo   %date% %time%
echo ============================================
echo.

echo [1/5] git pull...
cd /d D:\eaiselp\platform
git pull origin main
if errorlevel 1 echo   [警告] git pull 失败，用本地代码继续
echo.

echo [2/5] mvn clean package...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo   [错误] 打包失败
    pause
    exit /b 1
)
echo.

echo [3/5] 停旧服务...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8085 ^| findstr LISTENING') do (
    echo   杀进程 %%a (端口 8085)
    taskkill /pid %%a /f >nul 2>nul
)
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do (
    echo   杀进程 %%a (端口 8081)
    taskkill /pid %%a /f >nul 2>nul
)
ping -n 4 127.0.0.1 >nul
echo   [OK] 旧服务已停
echo.

echo [4/5] 启动 auth(8085) + runtime(8081)...
cd /d D:\eaiselp\platform
if "%GLM_API_KEY%"=="" (
    echo   [提示] GLM_API_KEY 未设置，请输入智谱 API Key：
    set /p GLM_API_KEY=GLM_API_KEY=
)
if "%JWT_SECRET%"=="" set JWT_SECRET=dev-placeholder-secret-key-for-eaiselp-m2-phase1

start "eaiselp-auth" cmd /k "cd /d D:\eaiselp\platform && set GLM_API_KEY=!GLM_API_KEY! && set JWT_SECRET=!JWT_SECRET! && java -jar eaiselp-auth\target\eaiselp-auth.jar"
echo   [OK] auth 启动中，等待 15 秒...
ping -n 16 127.0.0.1 >nul

start "eaiselp-runtime" cmd /k "cd /d D:\eaiselp\platform && set GLM_API_KEY=!GLM_API_KEY! && set JWT_SECRET=!JWT_SECRET! && java -jar eaiselp-runtime\target\eaiselp-runtime.jar"
echo   [OK] runtime 启动中
echo.

echo [5/5] 等待启动并验证...
ping -n 21 127.0.0.1 >nul
echo   === 测试登录 (admin/admin123) ===
curl -s -X POST http://localhost:8085/api/v1/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123\"}"
echo.
echo.

echo ============================================
echo   批次 04 完成！
echo.
echo   后端已更新（CORS OPTIONS 修复）。
echo.
echo   前端访问方式：
echo   1. 确保 Python HTTP 服务在跑（D:\eaiselp\web 下 python -m http.server 8080）
echo   2. 浏览器访问: http://localhost:8080/login.html
echo   3. 输入 admin / admin123 登录
echo.
echo   注意：config.js 已配好直连 172.16.180.166:8085/8081
echo ============================================
pause
endlocal
