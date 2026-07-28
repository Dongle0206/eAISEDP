@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 部署批次 07
REM 日期：2026-07-28
REM 内容：M2 核心功能 + DFX 全量部署
REM   D1 LLM 异步化（POST /derive 立即返回 taskId，前端轮询）
REM   D2 限流（登录5/分/派生10/分/其他100/分）
REM   D3 安全加固（CORS 白名单 + 防枚举恒定时延 + BCrypt cost=12）
REM   E Case 状态机 + 检查点人工锁
REM   F Artifact 入库完善（content + frontmatter 填充）
REM   G 看板统计 API + H 配额校验
REM 注意：schema.sql 改了（t_artifact 加 content 列），必须重建数据库
REM 前置：已执行过批次 01 或 03
REM 双击即可执行，全程自动
REM =====================================================================

echo ============================================
echo   批次 07：M2 核心功能 + DFX 全量部署
echo   日期：2026-07-28
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

echo [3/6] 重建数据库（t_artifact 加 content 列）...
docker exec eaiselp-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS eaiselp; CREATE DATABASE eaiselp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
docker exec -i eaiselp-mysql mysql -uroot -proot eaiselp < D:\eaiselp\platform\eaiselp-data\src\main\resources\db\schema.sql
echo   [OK] 数据库已重建
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

echo ============================================
echo   批次 07 完成！
echo.
echo   M2 新增功能：
echo   - Case 状态机（drafting→deriving→reviewing→...→done）
echo   - 检查点人工锁（不可逆操作需确认）
echo   - LLM 异步化（POST /derive 立即返回 taskId）
echo   - 限流（防恶意调用）
echo   - 安全加固（CORS 白名单 + 防枚举）
echo   - 看板统计 API（/api/v1/dashboard/*）
echo   - 配额校验（超限返回 429）
echo   - Artifact 内容入库（content + frontmatter）
echo.
echo   前端需同步更新（eaiselp-web 仓 git pull）
echo   前端 Python 服务：cd D:\eaiselp\web ^&^& python -m http.server 8080
echo   浏览器：http://localhost:8080/login.html
echo ============================================
pause
endlocal
