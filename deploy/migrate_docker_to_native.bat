@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 数据迁移: Docker MySQL → 原生 MySQL（可选，Docker 最后一次启动）
REM 日期：2026-08-20
REM
REM 前提: Docker Desktop 还能启动最后一次（导完就不再依赖）。
REM        已先运行 setup_mysql_native.bat（原生 MySQL 已就绪）。
REM
REM 步骤:
REM   1. docker start eaiselp-mysql（旧容器）
REM   2. mysqldump 全量导出 eaiselp 库（含 flyway_schema_history：
REM      保留 V1~V3 成功记录，V4/V5 失败记录由 #22 脚本兜底清理）
REM   3. docker stop（此后 Docker Desktop 可卸载）
REM   4. 导入原生 MySQL（幂等迁移自动收敛历史残留结构）
REM
REM 若 Docker 已彻底不可用 → 直接跑 dplexecute_22_native_mysql.bat 全新重建。
REM =====================================================================

echo ═══════════════════════════════════════════════════════
echo   eAISEDP 数据迁移: Docker → 原生 MySQL
echo   %date% %time%
echo ═══════════════════════════════════════════════════════
echo.

REM ============ 配置区（与 setup/部署脚本一致） ============
set "MYSQL_HOME=D:\eaiselp\mysql-8.0"
set "MYSQL_SVC=eaiselp-mysql"
set "MYSQL_PORT=3306"
set "MYSQL_ROOT_PWD=root"
set "OLD_CONTAINER=eaiselp-mysql"
set "DUMP_FILE=%TEMP%\eaiselp_docker_dump.sql"
REM =================================

set "MYSQL_CLI=%MYSQL_HOME%\bin\mysql.exe"
set "MYSQL_DUMP=%MYSQL_HOME%\bin\mysqldump.exe"

if not exist "%MYSQL_DUMP%" (
    echo × 未找到 %MYSQL_DUMP% —— 请先运行 setup_mysql_native.bat
    pause & exit /b 1
)

REM ============ Step 1: 启动旧 Docker 容器 ============
echo [1/4] 启动旧 Docker MySQL 容器（最后一次）...
docker start %OLD_CONTAINER%
if errorlevel 1 (
    echo   × Docker 不可用！无法迁移旧数据。
    echo     处理: 直接运行 dplexecute_22_native_mysql.bat 全新重建
    echo     （旧联测数据丢弃，表结构+seed 由 Flyway 重建）
    pause & exit /b 1
)
echo   等容器就绪（10秒）...
ping -n 11 127.0.0.1 >nul
docker exec %OLD_CONTAINER% mysql -uroot -p%MYSQL_ROOT_PWD% -e "SELECT 1" >nul 2>nul
if errorlevel 1 ( echo   × 旧容器 MySQL 未就绪！ & pause & exit /b 1 )
echo   √

REM ============ Step 2: 导出 ============
echo [2/4] mysqldump 导出 eaiselp 库...
docker exec %OLD_CONTAINER% mysqldump -uroot -p%MYSQL_ROOT_PWD% --single-transaction --set-gtid-purged=OFF --max_allowed_packet=64M --databases eaiselp > "%DUMP_FILE%"
if errorlevel 1 ( echo   × 导出失败！ & pause & exit /b 1 )
for %%z in ("%DUMP_FILE%") do echo   √ 导出 %%~zz 字节 → %DUMP_FILE%

REM ============ Step 3: 停容器（Docker 从此退役） ============
echo [3/4] 停止旧容器...
docker stop %OLD_CONTAINER% >nul 2>nul
echo   √ 已停止（Docker Desktop 之后可卸载）

REM ============ Step 4: 导入原生 MySQL ============
echo [4/4] 导入原生 MySQL ...
net start %MYSQL_SVC% >nul 2>nul
"%MYSQL_CLI%" -h127.0.0.1 -P%MYSQL_PORT% -uroot -p%MYSQL_ROOT_PWD% --max_allowed_packet=64M eaiselp < "%DUMP_FILE%"
if errorlevel 1 ( echo   × 导入失败！ & pause & exit /b 1 )
echo   √ 导入完成

REM 验证关键表 + flyway 历史
"%MYSQL_CLI%" -h127.0.0.1 -P%MYSQL_PORT% -uroot -p%MYSQL_ROOT_PWD% eaiselp -e "SELECT COUNT(*) AS tables_cnt FROM information_schema.tables WHERE table_schema='eaiselp'; SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;" 2>nul

echo.
echo ═══════════════════════════════════════════════════════
echo   数据迁移完成！
echo ═══════════════════════════════════════════════════════
echo.
echo   旧账号/Cause/租户数据已保留（admin/admin123 等可直接登录）。
echo   flyway_history 中 V4/V5 失败记录由部署脚本自动清理，
echo   残留表结构由幂等迁移自动收敛——下一步直接运行:
echo     dplexecute_22_native_mysql.bat
echo.
pause
