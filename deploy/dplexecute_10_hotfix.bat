@echo off
chcp 65001 >nul

REM =====================================================================
REM eAISEDP 热修复 #10——三 bug 修复部署脚本
REM 日期：2026-08-04
REM 修复内容：
REM   Bug1: 前端乱码（Python http.server 缺 charset）→ 前端仓 start-web.py
REM   Bug2: Case 不存在（雪花ID精度丢失 + 前端用错 id 字段）→ 后端 Long→String + 前端改 caseId
REM   Bug3: 角色未注册 po（前端短码 vs 后端 team- 前缀不匹配）→ 前端改 team- + 后端加兜底
REM
REM 前提：部署机已有 git clone 的 platform（后端）和 eaiselp-web-separate（前端）目录
REM =====================================================================

echo ============================================
echo   eAISEDP 热修复 #10——三 Bug 修复
echo   %date% %time%
echo ============================================
echo.

REM ---- 配置区（按实际路径修改）----
set "BACKEND_DIR=D:\eaiselp\platform"
set "WEB_DIR=D:\eaiselp\web"
set "BACKEND_REPO=https://github.com/Dongle0206/eAISEDP.git"
set "WEB_REPO=https://github.com/Dongle0206/eAISEDP-web.git"

REM ---- Step 1: 停服务 ----
echo [1/5] 停止后端服务（auth 8085 + runtime 8081）...
call "%~dp0_common.bat" :kill_services
echo   [OK]
echo.

REM ---- Step 2: 拉取后端最新代码 ----
echo [2/5] 更新后端代码...
if not exist "%BACKEND_DIR%" (
    echo   后端目录不存在，请先 git clone %BACKEND_REPO%
    echo   或修改本脚本的 BACKEND_DIR 变量指向实际路径
    pause
    exit /b 1
)
cd /d "%BACKEND_DIR%"
echo   git pull...
git pull origin main 2>nul
if errorlevel 1 (
    echo   [警告] git pull 失败，尝试继续...
)
echo   [OK]
echo.

REM ---- Step 3: 重新编译后端 ----
echo [3/5] 重新编译后端（需要 JDK 17+ 和 Maven）...
echo   检查 JDK 版本...
java -version 2>&1 | findstr /i "version"
echo.
echo   开始 Maven 编译（跳过测试加速，如需测试去掉 -DskipTests）...
cd /d "%BACKEND_DIR%"
call mvn clean package -DskipTests -q
if errorlevel 1 (
    echo   [错误] Maven 编译失败！请检查 JDK 版本是否为 17+
    echo   当前 JDK:
    java -version
    pause
    exit /b 1
)
echo   [OK] 编译完成
echo.

REM ---- Step 4: 拉取前端最新代码 ----
echo [4/5] 更新前端代码...
if not exist "%WEB_DIR%" (
    echo   前端目录不存在，请先 git clone %WEB_REPO%
    echo   或修改本脚本的 WEB_DIR 变量指向实际路径
    pause
    exit /b 1
)
cd /d "%WEB_DIR%"
echo   git pull...
git pull origin main 2>nul
if errorlevel 1 (
    echo   [警告] git pull 失败，尝试继续...
)
echo   [OK] 前端代码已更新（含 start-web.py 编码修复 + caseId/角色修复）
echo.

REM ---- Step 5: 启动服务 ----
echo [5/5] 启动后端服务...
call "%~dp0_common.bat" :start_services
echo   [OK]
echo.

REM ---- 验证 ----
echo ============================================
echo   部署完成！验证步骤：
echo ============================================
echo.
echo   1. 后端验证（等待 30 秒让服务启动）：
echo      curl http://localhost:8085/actuator/health
echo      curl http://localhost:8081/actuator/health
echo.
echo   2. 前端启动（在新 cmd 窗口）：
echo      cd /d %WEB_DIR%
echo      python start-web.py
echo.
echo   3. 浏览器访问：
echo      http://localhost:8080/login.html
echo      账号: admin / admin123
echo.
echo   4. 验证三个修复：
echo      - 用户名不再乱码（顶部栏显示正常中文）
echo      - Case 列表点击"查看"不再报"Case 不存在"
echo      - 发起派生选择角色不再报"角色未注册"
echo.
echo ============================================
pause
