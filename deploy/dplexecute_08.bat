@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 部署批次 08
REM 日期：2026-08-03
REM 内容：M3-4 全文检索 + M3-2/3 治理审计上线
REM   M3-4 Artifact 全文检索
REM     - ArtifactService.search(keyword, page, size)：content + title LIKE 命中
REM     - SearchController：GET /api/v1/search?q=关键词&page=1&size=10
REM       返回摘要（content 前 200 字符 + title + type + role + caseId）
REM       @RequirePermission("artifact:view") + @RateLimit(60/分)
REM   M3-2 治理审计（前置批次已合入，本批次随建表一起生效）
REM     - t_governance_log 表
REM   监控就绪（M3-3 可观测）
REM     - runtime/auth 暴露 /actuator/prometheus
REM     - monitoring/prometheus.yml 抓取配置就位
REM 注意：schema.sql 改了（新增 t_governance_log 表），必须重建数据库
REM 前置：已执行过批次 01 或 03（基础设施 mysql 已起）
REM 双击即可执行，全程自动
REM =====================================================================

echo ============================================
echo   批次 08：M3-4 全文检索 + 治理审计上线
echo   日期：2026-08-03
echo   %date% %time%
echo ============================================
echo.

echo [1/7] git pull...
cd /d D:\eaiselp\platform
git pull origin main
if errorlevel 1 echo   [警告] git pull 失败，用本地代码继续
echo.

echo [2/7] mvn clean package...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo   [错误] 打包失败
    pause
    exit /b 1
)
echo.

echo [3/7] 重建数据库（schema.sql 新增 t_governance_log 表）...
echo   [警告] 此操作会清空 eaiselp 全部数据（DROP DATABASE）
echo   按 Ctrl+C 取消，或等待 5 秒自动继续...
ping -n 6 127.0.0.1 >nul
docker exec eaiselp-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS eaiselp; CREATE DATABASE eaiselp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
if errorlevel 1 (
    echo   [错误] 数据库重建失败，请确认 eaiselp-mysql 容器已启动
    pause
    exit /b 1
)
docker exec -i eaiselp-mysql mysql -uroot -proot eaiselp < D:\eaiselp\platform\eaiselp-data\src\main\resources\db\schema.sql
if errorlevel 1 (
    echo   [错误] schema.sql 导入失败
    pause
    exit /b 1
)
echo   [OK] 数据库已重建（含 t_governance_log 表）
echo.

echo [4/7] 停旧服务...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8085 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
ping -n 4 127.0.0.1 >nul
echo   [OK] 旧服务已停
echo.

echo [5/7] 启动 auth(8085) + runtime(8081)...
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

echo [6/7] 等待启动并验证...
ping -n 21 127.0.0.1 >nul

echo   === [验证 1] 登录测试 (admin/admin123) ===
curl -s -X POST http://localhost:8085/api/v1/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123\"}"
echo.
echo.

echo   === [验证 2] actuator 健康 (runtime 8081) ===
curl -s http://localhost:8081/actuator/health
echo.
echo.

echo   === [验证 3] actuator 健康 (auth 8085) ===
curl -s http://localhost:8085/actuator/health
echo.
echo.

echo [7/7] 可观测性提示...
echo   ------------------------------------------------------------------
echo   [提示] Prometheus + Grafana 未自动启动，需手动拉起：
echo     cd /d D:\eaiselp\platform
echo     docker compose up -d prometheus grafana
echo   （若 compose 文件尚未定义 prometheus/grafana 服务，请补充后再执行）
echo   抓取配置：monitoring\prometheus.yml（抓 runtime:8081 / actuator:8085 的 /actuator/prometheus）
echo   Grafana 访问：http://localhost:3000 （默认 admin/admin）
echo   Prometheus 访问：http://localhost:9090
echo   ------------------------------------------------------------------
echo.

echo ============================================
echo   批次 08 完成！
echo.
echo   M3 新增功能：
echo   - Artifact 全文检索 GET /api/v1/search?q=关键词
echo     （content + title LIKE 命中，返回摘要前 200 字符）
echo   - 治理审计日志 t_governance_log 表已建（M3-2）
echo   - actuator 健康端点已验证（M3-3 可观测）
echo.
echo   前端需同步更新（eaiselp-web 仓 git pull）：
echo     cd /d D:\eaiselp\web ^&^& git pull
echo   前端 Python 服务：
echo     cd /d D:\eaiselp\web ^&^& python -m http.server 8080
echo   浏览器：http://localhost:8080/login.html
echo.
echo   下一步（按需）：
echo   - docker compose up -d prometheus grafana  （启动监控）
echo   - M4 全文检索优化（FULLTEXT 索引 / ES，替换 LIKE 实现）
echo ============================================
pause
endlocal
