@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 部署批次 02
REM 日期：2026-07-24
REM 内容：Tenant entity 修复热更新
REM 说明：修复 t_tenant 查询报 Unknown column 'tenant_id'
REM       只改了 Java 代码，不改 schema，不需要重建数据库
REM 前置：已执行过批次 01（数据库已有 5 权限表 + seed）
REM 双击即可执行，全程自动
REM =====================================================================

echo ============================================
echo   批次 02：Tenant 修复热更新
echo   日期：2026-07-24
echo   %date% %time%
echo ============================================
echo.

echo [1/4] git pull 最新代码...
cd /d D:\eaiselp\platform
git pull origin main
if errorlevel 1 echo   [警告] git pull 失败，用本地代码继续
echo.

echo [2/4] mvn clean package...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo   [错误] 打包失败
    pause
    exit /b 1
)
echo.

echo [3/4] 重启 auth(8085) + runtime(8081)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8085 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
ping -n 4 127.0.0.1 >nul
echo   [OK] 旧服务已停
echo.

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

echo [4/4] 等待启动并验证...
ping -n 21 127.0.0.1 >nul
echo   === 测试登录 (admin/admin123) ===
curl -s -X POST http://localhost:8085/api/v1/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123\"}"
echo.
echo.
echo   === 测试 capability overview ===
curl -s http://localhost:8081/api/capability/overview
echo.
echo.

echo ============================================
echo   批次 02 完成！
echo   浏览器: http://172.16.180.166:8081/eaiselp-web/login.html
echo   账号: admin / admin123
echo ============================================
pause
endlocal
