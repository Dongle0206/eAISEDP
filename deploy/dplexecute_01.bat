@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 部署批次 01
REM 日期：2026-07-23
REM 内容：M2 Phase1+2 首次部署
REM 说明：前端工程+auth(8085)+runtime(8081)+RBAC 5权限表+Case管理+派生+产物
REM       schema.sql 改了（加 5 权限表+seed），必须重建数据库
REM 前置：新机已装 Docker+JDK17+Maven，D:\eaiselp\platform 已 clone
REM 双击即可执行，全程自动
REM =====================================================================

echo ============================================
echo   批次 01：M2 Phase1+2 首次部署
echo   日期：2026-07-23
echo   %date% %time%
echo ============================================
echo.

echo [1/6] git pull 最新代码...
cd /d D:\eaiselp\platform
git pull origin main
if errorlevel 1 echo   [警告] git pull 失败，用本地代码继续
echo.

echo [2/6] mvn clean package...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo   [错误] 打包失败，检查 Maven/JDK 环境
    pause
    exit /b 1
)
echo.

echo [3/6] 重建数据库（旧数据会清空）...
docker exec eaiselp-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS eaiselp; CREATE DATABASE eaiselp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
docker exec -i eaiselp-mysql mysql -uroot -proot eaiselp < D:\eaiselp\platform\eaiselp-data\src\main\resources\db\schema.sql
echo   [OK] 数据库已重建（含 5 权限表 + admin seed）
echo.

echo [4/6] 停旧服务...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8085 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
ping -n 4 127.0.0.1 >nul
echo   [OK] 旧服务已停
echo.

echo [5/6] 启动 auth(8085) + runtime(8081)...
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

echo [6/6] 等待启动并验证...
ping -n 21 127.0.0.1 >nul
echo   === 测试登录 (admin/admin123) ===
curl -s -X POST http://localhost:8085/api/v1/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123\"}"
echo.
echo.

echo ============================================
echo   批次 01 完成！
echo   浏览器: http://172.16.180.166:8081/eaiselp-web/login.html
echo   账号: admin / admin123
echo ============================================
pause
endlocal
