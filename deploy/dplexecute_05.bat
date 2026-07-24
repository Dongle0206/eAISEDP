@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 部署批次 05
REM 日期：2026-07-24
REM 内容：Case 权限码修复（case:read → case:view，与 seed 对齐）
REM 说明：CaseController 标的 case:read 与 seed 的 case:view 不匹配导致 403
REM 前置：已执行过批次 03 或 04
REM 双击即可执行，全程自动
REM =====================================================================

echo ============================================
echo   批次 05：Case 权限码修复
echo   日期：2026-07-24
echo   %date% %time%
echo ============================================
echo.

echo [1/4] git pull...
cd /d D:\eaiselp\platform
git pull origin main
if errorlevel 1 echo   [警告] git pull 失败，用本地代码继续
echo.

echo [2/4] mvn clean package...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo   [错误] 打包失败
    pause
    exit /b 1
)
echo.

echo [3/4] 重启 runtime(8081)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
ping -n 4 127.0.0.1 >nul

cd /d D:\eaiselp\platform
if "%GLM_API_KEY%"=="" set GLM_API_KEY=3f3582bb3f2243fba844dea90cd2a75b.s7J8gzxCxCvbEw1U
if "%JWT_SECRET%"=="" set JWT_SECRET=dev-placeholder-secret-key-for-eaiselp-m2-phase1

start "eaiselp-runtime" cmd /k "cd /d D:\eaiselp\platform && set GLM_API_KEY=!GLM_API_KEY! && set JWT_SECRET=!JWT_SECRET! && java -jar eaiselp-runtime\target\eaiselp-runtime.jar"
echo   [OK] runtime 重启中（auth 不用重启）
echo.

echo [4/4] 等待启动...
ping -n 21 127.0.0.1 >nul
echo   [OK] runtime 已就绪
echo.

echo ============================================
echo   批次 05 完成！
echo   刷新浏览器，重新进入 Case 管理试试
echo ============================================
pause
endlocal
