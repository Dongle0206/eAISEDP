@echo off
chcp 65001 >nul

REM =====================================================================
REM eAISEDP Wave5 质量补全——部署脚本 #11
REM 日期：2026-08-04
REM 内容：
REM   1. SP-4: 清除 P6 硬编码（DerivationEngine.guessType → yml 配置）
REM   2. 新增 5 个单元测试文件（PermissionServiceImpl/CaseStateServiceImpl/
REM      AuditServiceImpl/MCPController/CaseStateServiceImpl 共 42 个测试用例）
REM   3. 删除 PermissionDemoController（Phase 2 清理）
REM   4. 前端 audit-log.html 连接真实 API
REM   5. _common.bat 标签分发修复
REM
REM 部署步骤：拉码 → 编译 → 启服务 → 验证
REM =====================================================================

echo ============================================
echo   eAISEDP Wave5 质量补全部署 #11
echo   %date% %time%
echo ============================================
echo.

REM ---- 配置区 ----
set "EAISELP_HOME=D:\eaiselp\platform"
set "WEB_DIR=D:\eaiselp\web"

REM ---- Step 1: 停服务 ----
echo [1/5] 停止后端服务...
call "%~dp0_common.bat" :kill_services
echo   [OK]
echo.

REM ---- Step 2: 拉取后端代码 ----
echo [2/5] 更新后端代码...
if not exist "%EAISELP_HOME%" (
    echo   [错误] 后端目录不存在: %EAISELP_HOME%
    echo   请先 git clone 或修改 EAISELP_HOME 变量
    pause
    exit /b 1
)
cd /d "%EAISELP_HOME%"
git pull origin main
if errorlevel 1 echo   [警告] git pull 有冲突，请手动检查
echo   [OK]
echo.

REM ---- Step 3: 编译后端 ----
echo [3/5] 重新编译后端...
echo   JDK 版本:
java -version 2>&1
echo.
cd /d "%EAISELP_HOME%"
call mvn clean package -DskipTests
if errorlevel 1 (
    echo   [错误] 编译失败！
    pause
    exit /b 1
)
echo   [OK] 编译完成
echo.

REM ---- Step 4: 拉取前端代码 ----
echo [4/5] 更新前端代码...
if exist "%WEB_DIR%" (
    cd /d "%WEB_DIR%"
    git pull origin main
    echo   [OK]
) else (
    echo   [提示] 前端目录不存在: %WEB_DIR%，跳过前端更新
)
echo.

REM ---- Step 5: 启动服务 ----
echo [5/5] 启动后端服务...
call "%~dp0_common.bat" :start_services
if errorlevel 1 (
    echo   [错误] 启动失败，请检查上方输出
    pause
    exit /b 1
)
echo   [OK]
echo.

REM ---- 验证 ----
echo ============================================
echo   部署完成！
echo ============================================
echo.
echo   后端验证（等 30 秒）：
echo     curl http://localhost:8085/actuator/health
echo     curl http://localhost:8081/actuator/health
echo.
echo   前端启动（新 cmd 窗口）：
echo     cd /d %WEB_DIR%
echo     python start-web.py
echo.
echo   浏览器访问：
echo     http://localhost:8080/login.html
echo     账号: admin / admin123
echo.
echo   Wave5 新增验证点：
echo     - 审计日志页面（需 tenant_admin 登录）有数据
echo     - 发起派生后产物类型正确（prd/design/code/review/test...）
echo     - /api/v1/demo/* 已移除（404 正常）
echo.
pause
