@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 部署批次 09
REM 日期：2026-08-03
REM 内容：P2修复+M4高可用+前端异步轮询 全量部署
REM   - P2: frontmatter ObjectMapper + stage 语义修复
REM   - P2: User.password @JsonIgnore（密码泄漏修复）
REM   - P2: CircuitBreaker 冷却时间戳修复 + 6 单测
REM   - M4-1: Nginx 多实例负载均衡配置
REM   - M4-5: 前端异步轮询 UI
REM 注意：schema.sql 改了（t_artifact 加 content + t_governance_log），必须重建数据库
REM 前置：已执行过批次 01 或 03
REM 双击即可执行，全程自动
REM =====================================================================

echo ============================================
echo   批次 09：P2修复+M4高可用 全量部署
echo   日期：2026-08-03
echo   %date% %time%
echo ============================================
echo.

echo [1/6] git pull...
cd /d D:\eaiselp\platform
git pull origin main
if errorlevel 1 echo   [警告] git pull 失败，用本地代码继续
echo.

echo [2/6] mvn clean package...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo   [错误] 打包失败
    pause
    exit /b 1
)
echo.

echo [3/6] 重建数据库...
docker exec eaiselp-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS eaiselp; CREATE DATABASE eaiselp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
docker exec -i eaiselp-mysql mysql -uroot -proot eaiselp < D:\eaiselp\platform\eaiselp-data\src\main\resources\db\schema.sql
echo   [OK] 数据库已重建（含 t_governance_log + t_artifact.content + t_model_routing）
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
set JWT_SECRET=dev-placeholder-secret-must-be-at-least-32-bytes-long-for-hs256-algorithm

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
echo   === 测试 actuator/health ===
curl -s http://localhost:8081/actuator/health
echo.
echo.

echo ============================================
echo   批次 09 完成！
echo.
echo   本次更新：
echo   - User.password @JsonIgnore（密码不泄漏前端）
echo   - CircuitBreaker 冷却修复（熔断器保护有效）
echo   - frontmatter ObjectMapper（JSON 严格列安全）
echo   - Artifact.stage 语义修复
echo   - Nginx 多实例负载均衡配置（deploy/nginx/）
echo.
echo   前端更新：
echo   - cd D:\eaiselp\web ^&^& git pull origin main
echo   - config.js 默认同源模式（走 Nginx 80 端口）
echo   - Case 详情页异步轮询 UI
echo.
echo   高可用部署（可选）：
echo   - 双击 deploy\start-ha.bat 启动 auth×2 + runtime×2 + Nginx
echo ============================================
pause
endlocal
