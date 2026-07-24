@echo off
chcp 65001 >nul

REM =====================================================================
REM eAISEDP 前端修复 v3——最简方案
REM 日期：2026-07-24
REM 策略：停掉 Docker nginx（Docker 网络问题多），前端由 runtime 托管。
REM       前端文件复制到 runtime 的 static 目录，config.js 配同源。
REM       登录 API 由 runtime 代理到 auth（RuntimeController 加 /auth/login 转发）。
REM       或者更简单：config.js 直连 auth 8085（CORS 已配）。
REM
REM 本脚本做 3 件事：
REM   1. 停 Docker nginx
REM   2. 更新前端 config.js（直连 auth+runtime，不依赖 nginx）
REM   3. 用 Python/Node 简单服务托管前端（如果有的话），否则提示手动开
REM =====================================================================

echo ============================================
echo   前端修复 v3——去掉 Docker nginx
echo   %date% %time%
echo ============================================
echo.

echo [1/3] 停掉 Docker nginx...
docker rm -f eaiselp-web 2>nul
echo   [OK]
echo.

echo [2/3] 更新前端 config.js...
cd /d D:\eaiselp\web
(
echo /**
echo  * eAISEDP 前端配置——直连后端（不依赖 nginx 代理）
echo  */
echo window.EAISELP_CONFIG = {
echo   AUTH_BASE_URL: 'http://172.16.180.166:8085',
echo   API_BASE_URL: 'http://172.16.180.166:8081',
echo   TOKEN_KEY: 'eaiselp_token',
echo   LOGIN_PAGE: 'login.html',
echo   INDEX_PAGE: 'index.html'
echo };
) > config.js
echo   [OK] config.js 已更新为直连 172.16.180.166
echo.

echo [3/3] 启动简单 HTTP 服务托管前端...
echo.
echo 选择一种方式（选你能用的）：
echo.
echo   方式 A（推荐）: 如果装了 Python
echo     cd /d D:\eaiselp\web
echo     python -m http.server 8080
echo     浏览器访问: http://localhost:8080/login.html
echo.
echo   方式 B: 如果装了 Node.js
echo     cd /d D:\eaiselp\web
echo     npx http-server -p 8080
echo     浏览器访问: http://localhost:8080/login.html
echo.
echo   方式 C: 直接用浏览器打开文件（file:// 协议）
echo     在浏览器地址栏输入: file:///D:/eaiselp/web/login.html
echo     注意: file:// 协议下 AJAX 跨域策略可能不同，如果不行用方式 A
echo.

REM 检查 Python 是否可用
python --version >nul 2>nul
if not errorlevel 1 (
    echo [自动启动] 检测到 Python，自动启动 HTTP 服务...
    echo   浏览器访问: http://localhost:8080/login.html
    echo   按 Ctrl+C 停止
    echo.
    cd /d D:\eaiselp\web
    python -m http.server 8080
    goto end
)

REM 检查 Node 是否可用
where npx >nul 2>nul
if not errorlevel 1 (
    echo [自动启动] 检测到 Node.js，自动启动 HTTP 服务...
    cd /d D:\eaiselp\web
    npx http-server -p 8080
    goto end
)

echo [提示] 新机没有 Python 和 Node.js。
echo   安装 Python（最简单）:
echo     winget install Python.Python.3.12
echo   安装后重开 cmd，再跑本脚本。
echo.
echo   或者直接用浏览器打开: file:///D:/eaiselp/web/login.html

:end
pause
