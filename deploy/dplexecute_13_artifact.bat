@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 一键部署脚本 #13——产出落地全链路 + 全部前端功能
REM 日期：2026-08-14
REM
REM 本批次更新内容：
REM   [后端] 产出落地全链路（Step 1-4）：
REM     1. ArtifactFileService——AI 产出解析代码块写入工作区文件系统
REM     2. GitService——JGit 真实 git init/commit/push
REM     3. CICDTriggerService——Webhook 通用触发（Jenkins/GitLab/GitHub/Gitea）
REM     4. 编排模式集成——每步派生后自动落文件，完成后自动 commit+push+触发CI
REM   [后端] 新增管理 API：
REM     SystemManageController（/api/v1/roles|permissions|model-routing|quotas）
REM     工作区 API（/api/runtime/workspace/{caseId}/files|read）
REM   [前端] 6 个新页面：
REM     workspace.html（工作区文件浏览）
REM     checkpoint.html（检查点审批）
REM     model-routing.html（模型路由配置）
REM     role-list.html（角色管理）
REM     quota.html（配额管理）
REM     monitor.html（系统监控）
REM
REM 新增配置项（全部可选，不配也能用）：
REM   set WORKSPACE_ROOT=D:\eaiselp\workspaces   产出文件根目录（默认 ./workspaces）
REM   set GIT_REMOTE_URL=https://xxx.git          Git 远程仓库（不配=只本地 commit）
REM   set GIT_TOKEN=xxx                           Git 访问 token
REM   set CICD_WEBHOOK_URL=http://xxx/hook        CI/CD Webhook（不配=跳过触发）
REM   set CICD_WEBHOOK_TOKEN=xxx                  Webhook 认证 token
REM =====================================================================

echo ═══════════════════════════════════════════════════════
echo   eAISEDP 部署 #13——产出落地全链路 + 全部前端功能
echo   %date% %time%
echo ═══════════════════════════════════════════════════════
echo.

REM ============ 配置区 ============
set "PLATFORM_DIR=D:\eaiselp\platform"
set "WEB_DIR=D:\eaiselp\web"
set "MYSQL_CONTAINER=eaiselp-mysql"
set "MYSQL_ROOT_PWD=root"
set "JAVA_HOME="

REM ★ 按需配置（不配也能用，产出只落本地文件+本地Git commit）
REM set "GIT_REMOTE_URL=https://your-gitlab.com/your-repo.git"
REM set "GIT_TOKEN=your_git_token"
REM set "CICD_WEBHOOK_URL=http://your-jenkins/generic-webhook-trigger/invoke"
REM set "CICD_WEBHOOK_TOKEN=your_webhook_token"
REM =================================

set "JWT_SECRET=dev-placeholder-secret-must-be-at-least-32-bytes-long-for-hs256-algorithm"
set "GLM_API_KEY=%GLM_API_KEY%"

REM ============ JDK17 自动探测 ============
if "%JAVA_HOME%"=="" (
    echo [JDK] 自动探测 JDK17...
    set "JDK_FOUND="
    for %%d in ("C:\Program Files\Eclipse Adoptium" "C:\Program Files\Java" "C:\Program Files\Microsoft" "C:\Program Files\Zulu" "C:\Program Files\BellSoft" "D:\jdk" "D:\Java") do (
        if exist %%d (
            for /d %%j in (%%d\jdk-17*) do (
                if exist "%%j\bin\java.exe" (
                    set "JAVA_HOME=%%j"
                    set "JDK_FOUND=1"
                )
            )
        )
    )
    if not defined JDK_FOUND (
        echo [JDK] × 未找到 JDK17！请设置 JAVA_HOME 或安装：winget install EclipseAdoptium.Temurin.17.JDK
        pause
        exit /b 1
    )
    echo [JDK] 找到: !JAVA_HOME!
)

echo [配置] 后端: %PLATFORM_DIR%
echo [配置] 前端: %WEB_DIR%
echo [配置] JDK: %JAVA_HOME%
if defined GIT_REMOTE_URL (echo [配置] Git 远程: %GIT_REMOTE_URL%) else (echo [配置] Git 远程: 未配置（只本地 commit）)
if defined CICD_WEBHOOK_URL (echo [配置] CI/CD Webhook: %CICD_WEBHOOK_URL%) else (echo [配置] CI/CD Webhook: 未配置（跳过触发）)
echo.

REM ============ Step 1: 停旧服务 ============
echo [1/6] 停止旧服务...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8085 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
ping -n 4 127.0.0.1 >nul
echo   √ 已停止
echo.

REM ============ Step 2: 拉代码 ============
echo [2/6] 拉取最新代码...
cd /d "%PLATFORM_DIR%"
git pull origin main 2>nul
cd /d "%WEB_DIR%"
git pull origin main 2>nul
echo   √ 代码已更新
echo.

REM ============ Step 3: 编译后端 ============
echo [3/6] 编译后端（1-2 分钟）...
cd /d "%PLATFORM_DIR%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call mvn clean package -DskipTests -q
if errorlevel 1 (
    echo   × 编译失败！
    pause
    exit /b 1
)
echo   √ 编译完成
echo.

REM ============ Step 4: 启动后端 ============
echo [4/6] 启动后端...
set "TMP_AUTH=%TEMP%\eaiselp_start_auth.bat"
> "%TMP_AUTH%" echo @echo off
>> "%TMP_AUTH%" echo chcp 65001 ^>nul
>> "%TMP_AUTH%" echo cd /d "%PLATFORM_DIR%"
>> "%TMP_AUTH%" echo set "JWT_SECRET=%JWT_SECRET%"
>> "%TMP_AUTH%" echo set "GLM_API_KEY=%GLM_API_KEY%"
>> "%TMP_AUTH%" echo set "MYSQL_HOST=127.0.0.1"
>> "%TMP_AUTH%" echo set "MYSQL_PASSWORD=%MYSQL_ROOT_PWD%"
if defined GIT_REMOTE_URL (>> "%TMP_AUTH%" echo set "GIT_REMOTE_URL=%GIT_REMOTE_URL%")
if defined GIT_TOKEN (>> "%TMP_AUTH%" echo set "GIT_TOKEN=%GIT_TOKEN%")
if defined CICD_WEBHOOK_URL (>> "%TMP_AUTH%" echo set "CICD_WEBHOOK_URL=%CICD_WEBHOOK_URL%")
if defined CICD_WEBHOOK_TOKEN (>> "%TMP_AUTH%" echo set "CICD_WEBHOOK_TOKEN=%CICD_WEBHOOK_TOKEN%")
>> "%TMP_AUTH%" echo echo === Auth 启动中... 8085 ===
>> "%TMP_AUTH%" echo java -jar eaiselp-auth\target\eaiselp-auth.jar
>> "%TMP_AUTH%" echo pause ^>nul
start "eaiselp-auth" "%TMP_AUTH%"

ping -n 21 127.0.0.1 >nul

set "TMP_RT=%TEMP%\eaiselp_start_runtime.bat"
> "%TMP_RT%" echo @echo off
>> "%TMP_RT%" echo chcp 65001 ^>nul
>> "%TMP_RT%" echo cd /d "%PLATFORM_DIR%"
>> "%TMP_RT%" echo set "JWT_SECRET=%JWT_SECRET%"
>> "%TMP_RT%" echo set "GLM_API_KEY=%GLM_API_KEY%"
>> "%TMP_RT%" echo set "MYSQL_HOST=127.0.0.1"
>> "%TMP_RT%" echo set "MYSQL_PASSWORD=%MYSQL_ROOT_PWD%"
if defined GIT_REMOTE_URL (>> "%TMP_RT%" echo set "GIT_REMOTE_URL=%GIT_REMOTE_URL%")
if defined GIT_TOKEN (>> "%TMP_RT%" echo set "GIT_TOKEN=%GIT_TOKEN%")
if defined CICD_WEBHOOK_URL (>> "%TMP_RT%" echo set "CICD_WEBHOOK_URL=%CICD_WEBHOOK_URL%")
if defined CICD_WEBHOOK_TOKEN (>> "%TMP_RT%" echo set "CICD_WEBHOOK_TOKEN=%CICD_WEBHOOK_TOKEN%")
>> "%TMP_RT%" echo set "SYSTEM_PATH=../agents-config"
>> "%TMP_RT%" echo echo === Runtime 启动中... 8081 ===
>> "%TMP_RT%" echo java -jar eaiselp-runtime\target\eaiselp-runtime.jar
>> "%TMP_RT%" echo pause ^>nul
start "eaiselp-runtime" "%TMP_RT%"

echo   等待 runtime 启动（20秒）...
ping -n 21 127.0.0.1 >nul
echo   √ 后端已启动
echo.

REM ============ Step 5: 启动前端 ============
echo [5/6] 启动前端...
cd /d "%WEB_DIR%"
start "eaiselp-web" cmd /k "cd /d %WEB_DIR% && python start-web.py"
ping -n 4 127.0.0.1 >nul
echo   √ 前端已启动
echo.

REM ============ Step 6: 验证 ============
echo [6/6] 验证服务...
echo.
echo   auth:   & curl -s --connect-timeout 5 http://localhost:8085/actuator/health
echo.
echo   runtime:& curl -s --connect-timeout 5 http://localhost:8081/actuator/health
echo.
echo.

echo ═══════════════════════════════════════════════════════
echo   部署完成！
echo ═══════════════════════════════════════════════════════
echo.
echo   访问: http://localhost:8080/login.html
echo   账号: admin / admin123
echo.
echo   本批次新增功能验证：
echo     1. Case 详情页 → 一键编排（输入需求自动 6 步）
echo     2. 编排完成后 → 左侧菜单"工作区文件"看产出的代码文件
echo     3. 左侧菜单"产物查看"看 AI 产出内容
echo     4. 管理员菜单：角色管理/模型路由/配额管理/系统监控/检查点审批
echo.
echo   产出落地验证：
echo     dir /s /b %PLATFORM_DIR%\workspaces\
echo     （应有 {caseId}\{role}\*.md 和代码文件）
echo     cd workspaces\{caseId} ^&^& git log（应有 commit 记录）
echo.
if "%GLM_API_KEY%"=="" (
    echo   ⚠ GLM_API_KEY 未设置！编排会失败。
    echo     set GLM_API_KEY=你的智谱Key 后重启 runtime
)
echo.
pause
