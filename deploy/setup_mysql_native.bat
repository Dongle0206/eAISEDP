@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 环境搭建: Windows 原生 MySQL 8.0（替换 Docker Desktop）
REM 日期：2026-08-20
REM
REM 背景：测试机 Docker Desktop 不再可用（企业商用许可限制）。
REM 原生 MySQL 8.0 ZIP 免安装版 + Windows 服务，零 Docker 依赖，
REM 应用连接参数不变（127.0.0.1:3306 / root / root）。
REM
REM 本脚本一次性执行（重跑安全，已装部分自动跳过）：
REM   1. 检测/解压 MySQL ZIP 到 D:\eaiselp\mysql-8.0
REM      （ZIP 手动下载放 deploy\ 目录，下载地址见下方提示）
REM   2. 写 my.ini（utf8mb4 + 3306 + 大包）
REM   3. mysqld --initialize-insecure（data 目录已存在则跳过）
REM   4. 注册 Windows 服务 eaiselp-mysql（已存在则跳过）
REM   5. net start + 设 root 密码 root + 建库 eaiselp(utf8mb4)
REM   6. 验证 SELECT VERSION()
REM
REM MySQL ZIP 手动下载（二选一，约 230MB）：
REM   官网:   https://dev.mysql.com/downloads/mysql/
REM           选择 Windows (x86, 64-bit), ZIP Archive
REM           如 mysql-8.0.40-winx64.zip
REM   清华镜像: https://mirrors.tuna.tsinghua.edu.cn/mysql/downloads/MySQL-8.0/
REM           mysql-8.0.40-winx64.zip
REM   下载后放到本脚本同目录（deploy\）再运行本脚本
REM =====================================================================

echo ═══════════════════════════════════════════════════════
echo   eAISEDP 原生 MySQL 环境搭建（替换 Docker Desktop）
echo   %date% %time%
echo ═══════════════════════════════════════════════════════
echo.

REM ============ 配置区 ============
set "MYSQL_HOME=D:\eaiselp\mysql-8.0"
set "MYSQL_SVC=eaiselp-mysql"
set "MYSQL_PORT=3306"
set "MYSQL_ROOT_PWD=root"
set "ZIP_DIR=%~dp0"
REM =================================

REM ============ Step 1: 定位 MySQL ============
echo [1/6] 定位 MySQL ...
set "MYSQLD=%MYSQL_HOME%\bin\mysqld.exe"
if exist "%MYSQLD%" (
    echo   √ 已安装: %MYSQLD%
    goto :step2
)
REM 未安装 → 找 deploy 目录下的 ZIP 自动解压（Windows 10+ 自带 tar 可解 zip）
set "FOUND_ZIP="
for %%f in ("%ZIP_DIR%mysql-8.0.*-winx64.zip") do set "FOUND_ZIP=%%f"
if not defined FOUND_ZIP (
    echo   × 未找到 %MYSQL_HOME%\bin\mysqld.exe，deploy 目录也无 MySQL ZIP
    echo.
    echo   请手动下载 MySQL 8.0 ZIP（Windows x64, ZIP Archive）：
    echo     官网:     https://dev.mysql.com/downloads/mysql/
    echo     清华镜像: https://mirrors.tuna.tsinghua.edu.cn/mysql/downloads/MySQL-8.0/
    echo   下载后放到: %ZIP_DIR%
    echo   再重新运行本脚本（自动解压安装）。
    echo.
    echo   或者已解压到其他位置: 编辑本脚本配置区 MYSQL_HOME 指向解压目录。
    pause & exit /b 1
)
echo   解压 !FOUND_ZIP! → D:\eaiselp\ ...
if not exist "D:\eaiselp" mkdir "D:\eaiselp"
tar -xf "!FOUND_ZIP!" -C "D:\eaiselp"
if errorlevel 1 ( echo   × 解压失败！ & pause & exit /b 1 )
REM ZIP 内目录形如 mysql-8.0.40-winx64 → 改名/对齐为 mysql-8.0
for /d %%d in ("D:\eaiselp\mysql-8.0.*-winx64") do (
    if not exist "%MYSQL_HOME%" ren "%%d" "mysql-8.0"
)
if not exist "%MYSQLD%" (
    echo   × 解压后仍未找到 mysqld.exe，请检查 ZIP 结构，或将解压目录改名为:
    echo     %MYSQL_HOME%
    pause & exit /b 1
)
echo   √ 安装完成: %MYSQLD%

:step2
REM ============ Step 2: my.ini ============
echo [2/6] 写配置 my.ini ...
if exist "%MYSQL_HOME%\my.ini" (
    echo   √ 已存在，跳过
) else (
    > "%MYSQL_HOME%\my.ini" echo [mysqld]
    >> "%MYSQL_HOME%\my.ini" echo port=%MYSQL_PORT%
    >> "%MYSQL_HOME%\my.ini" echo datadir=%MYSQL_HOME:/=%/data
    >> "%MYSQL_HOME%\my.ini" echo character-set-server=utf8mb4
    >> "%MYSQL_HOME%\my.ini" echo collation-server=utf8mb4_unicode_ci
    >> "%MYSQL_HOME%\my.ini" echo default-authentication-plugin=mysql_native_password
    >> "%MYSQL_HOME%\my.ini" echo max_allowed_packet=64M
    >> "%MYSQL_HOME%\my.ini" echo [client]
    >> "%MYSQL_HOME%\my.ini" echo port=%MYSQL_PORT%
    >> "%MYSQL_HOME%\my.ini" echo default-character-set=utf8mb4
    echo   √ 已写入 %MYSQL_HOME%\my.ini
)

REM ============ Step 3: 端口检查 ============
echo [3/6] 检查端口 %MYSQL_PORT% ...
netstat -ano | findstr ":%MYSQL_PORT% " | findstr LISTENING >nul 2>nul
if not errorlevel 1 (
    echo   ⚠ 端口 %MYSQL_PORT% 已被占用！
    echo     可能原因: Docker Desktop 的旧 MySQL 容器还在监听。
    echo     处理: 停止 Docker Desktop（系统托盘退出）后重跑本脚本；
    echo     或编辑本脚本配置区 MYSQL_PORT 换端口（同时改部署脚本的 MYSQL_PORT）。
    pause & exit /b 1
)
echo   √ 端口空闲

REM ============ Step 4: 初始化 data ============
echo [4/6] 初始化数据目录 ...
if exist "%MYSQL_HOME%\data\mysql" (
    echo   √ data 目录已初始化，跳过（重装保护）
) else (
    "%MYSQLD%" --initialize-insecure --defaults-file="%MYSQL_HOME%\my.ini" --console
    if errorlevel 1 ( echo   × 初始化失败！ & pause & exit /b 1 )
    echo   √ 初始化完成（root 初始为空密码，Step 6 设为 root）
)

REM ============ Step 5: 注册并启动 Windows 服务 ============
echo [5/6] 注册 Windows 服务 %MYSQL_SVC% ...
sc query %MYSQL_SVC% >nul 2>nul
if not errorlevel 1 (
    echo   √ 服务已注册
) else (
    "%MYSQL_HOME%\bin\mysqld.exe" --install %MYSQL_SVC% --defaults-file="%MYSQL_HOME%\my.ini"
    if errorlevel 1 ( echo   × 服务注册失败（需管理员权限运行本脚本）！ & pause & exit /b 1 )
    echo   √ 服务已注册（开机自启）
)
net start %MYSQL_SVC% >nul 2>nul
if errorlevel 1 ( echo   ⚠ 服务启动命令返回非零（可能已在运行） ) else ( echo   √ 服务已启动 )
ping -n 4 127.0.0.1 >nul

REM ============ Step 6: 设密码 + 建库 + 验证 ============
echo [6/6] 设置 root 密码 + 建库 + 验证 ...
set "MYSQL_CLI=%MYSQL_HOME%\bin\mysql.exe"
REM 先试空密码（首次初始化），失败再试 root（重跑场景已设过密码）
"%MYSQL_CLI%" -h127.0.0.1 -P%MYSQL_PORT% -uroot --skip-password -e "SELECT 1" >nul 2>nul
if not errorlevel 1 (
    "%MYSQL_CLI%" -h127.0.0.1 -P%MYSQL_PORT% -uroot --skip-password -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '%MYSQL_ROOT_PWD%'; ALTER USER 'root'@'127.0.0.1' IDENTIFIED BY '%MYSQL_ROOT_PWD%';" >nul 2>nul
    echo   √ root 密码已设置为 %MYSQL_ROOT_PWD%
) else (
    echo   √ root 密码已存在（跳过设置）
)
"%MYSQL_CLI%" -h127.0.0.1 -P%MYSQL_PORT% -uroot -p%MYSQL_ROOT_PWD% -e "CREATE DATABASE IF NOT EXISTS eaiselp DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" >nul 2>nul
if errorlevel 1 ( echo   × 建库失败！ & pause & exit /b 1 )
for /f "usebackq delims=" %%v in (`"%MYSQL_CLI%" -h127.0.0.1 -P%MYSQL_PORT% -uroot -p%MYSQL_ROOT_PWD% -e "SELECT VERSION();" 2^>nul ^| findstr /R "^[0-9]"`) do set "VER=%%v"
echo   √ MySQL !VER! 就绪，库 eaiselp 已建

echo.
echo ═══════════════════════════════════════════════════════
echo   原生 MySQL 环境就绪！
echo ═══════════════════════════════════════════════════════
echo.
echo   连接: 127.0.0.1:%MYSQL_PORT%  root/%MYSQL_ROOT_PWD%  库 eaiselp
echo   服务: %MYSQL_SVC%（开机自启; 停止 net stop %MYSQL_SVC%）
echo.
echo   下一步（二选一）:
echo   A. 迁移 Docker 旧数据（Docker 还能最后一次启动时）:
echo      migrate_docker_to_native.bat
echo   B. 全新重建（测试数据可弃，推荐简单路径）:
echo      dplexecute_22_native_mysql.bat
echo      （Flyway 启动时自动建全部表+seed，admin/admin123 可登录）
echo.
pause
