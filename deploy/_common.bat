@echo off
REM =====================================================================
REM eAISEDP 公共操作库——被各 dplexecute_NN.bat 调用
REM 用法：call deploy\_common.bat :函数名 [参数]
REM =====================================================================

REM 停服务（kill auth 8085 + runtime 8081）
:kill_services
echo [公共] 停止 auth(8085) + runtime(8081)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8085 ^| findstr LISTENING') do (
    echo   杀进程 %%a (端口 8085)
    taskkill /pid %%a /f >nul 2>nul
)
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do (
    echo   杀进程 %%a (端口 8081)
    taskkill /pid %%a /f >nul 2>nul
)
ping -n 4 127.0.0.1 >nul
exit /b 0

REM 启动服务（auth 8085 先启，等 15 秒，再启 runtime 8081）
:start_services
cd /d D:\eaiselp\platform
if "%GLM_API_KEY%"=="" (
    echo [提示] GLM_API_KEY 未设置，请输入智谱 API Key：
    set /p GLM_API_KEY=GLM_API_KEY=
)
if "%JWT_SECRET%"=="" set JWT_SECRET=dev-placeholder-secret-key-for-eaiselp-m2-phase1

echo [公共] 启动 auth(8085)...
start "eaiselp-auth" cmd /k "cd /d D:\eaiselp\platform && set GLM_API_KEY=!GLM_API_KEY! && set JWT_SECRET=!JWT_SECRET! && java -jar eaiselp-auth\target\eaiselp-auth.jar"

echo [公共] 等待 auth 启动（15秒）...
ping -n 16 127.0.0.1 >nul

echo [公共] 启动 runtime(8081)...
start "eaiselp-runtime" cmd /k "cd /d D:\eaiselp\platform && set GLM_API_KEY=!GLM_API_KEY! && set JWT_SECRET=!JWT_SECRET! && java -jar eaiselp-runtime\target\eaiselp-runtime.jar"

exit /b 0

REM 验证登录
:verify
echo [公共] 等待服务就绪（20秒）...
ping -n 21 127.0.0.1 >nul
echo.
echo === 测试登录 (admin/admin123) ===
curl -s -X POST http://localhost:8085/api/v1/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123\"}"
echo.
echo.
echo === 测试 capability overview ===
curl -s http://localhost:8081/api/capability/overview
echo.
echo.
echo 浏览器访问: http://172.16.180.166:8081/eaiselp-web/login.html
echo 账号: admin / admin123
exit /b 0
