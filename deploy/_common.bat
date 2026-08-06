@echo off
chcp 65001 >nul

REM =====================================================================
REM eAISEDP 公共操作库——被各 dplexecute_NN.bat 调用
REM
REM 用法：call "%~dp0_common.bat" :标签名
REM   call "%~dp0_common.bat" :kill_services
REM   call "%~dp0_common.bat" :start_services
REM   call "%~dp0_common.bat" :verify
REM
REM ⚠️ 关键：开头必须有 goto 分发，否则 bat 从头顺序执行，
REM    call :start_services 时会先跑 :kill_services 然后 exit，
REM    永远执行不到 start_services！
REM =====================================================================

REM ---- 参数分发 ----
if "%~1"=="" (
    echo [用法] call _common.bat :标签名
    echo   :kill_services   停止 auth(8085) + runtime(8081)
    echo   :start_services  启动 auth + runtime
    echo   :verify          验证登录
    exit /b 0
)
goto %~1


REM =====================================================================
REM 停服务（kill auth 8085 + runtime 8081）
REM =====================================================================
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
echo [公共] 服务已停止
exit /b 0


REM =====================================================================
REM 启动服务（auth 8085 先启，等 15 秒，再启 runtime 8081）
REM =====================================================================
:start_services

REM ---- 路径配置（通过环境变量覆盖，默认 D:\eaiselp\platform）----
if "%EAISELP_HOME%"=="" set "EAISELP_HOME=D:\eaiselp\platform"

if not exist "%EAISELP_HOME%" (
    echo [错误] 后端目录不存在: %EAISELP_HOME%
    echo   请设置环境变量 EAISELP_HOME 指向 platform 目录
    echo   或修改本脚本的 EAISELP_HOME 默认值
    exit /b 1
)

REM ---- 环境变量 ----
if "%JWT_SECRET%"=="" (
    set "JWT_SECRET=dev-placeholder-secret-must-be-at-least-32-bytes-long-for-hs256-algorithm"
    echo [公共] JWT_SECRET 未设置，使用默认值（仅开发环境！生产必须改）
)

if "%GLM_API_KEY%"=="" (
    echo [提示] GLM_API_KEY 未设置，派生功能将不可用。
    echo   如需派生功能，请设置: set GLM_API_KEY=你的智谱API密钥
    set "GLM_API_KEY="
)

REM ---- jar 路径检查 ----
set "AUTH_JAR=%EAISELP_HOME%\eaiselp-auth\target\eaiselp-auth.jar"
set "RUNTIME_JAR=%EAISELP_HOME%\eaiselp-runtime\target\eaiselp-runtime.jar"

if not exist "%AUTH_JAR%" (
    echo [错误] auth jar 不存在: %AUTH_JAR%
    echo   请先编译: cd /d "%EAISELP_HOME%" ^&^& mvn clean package -DskipTests
    exit /b 1
)
if not exist "%RUNTIME_JAR%" (
    echo [错误] runtime jar 不存在: %RUNTIME_JAR%
    echo   请先编译: cd /d "%EAISELP_HOME%" ^&^& mvn clean package -DskipTests
    exit /b 1
)

echo [公共] 启动 auth(8085)...
echo   目录: %EAISELP_HOME%
echo   JWT_SECRET: 已设置

REM 生成启动脚本到临时文件，避免 cmd 引号嵌套陷阱
REM auth 启动
set "TMP_AUTH=%TEMP%\eaiselp_start_auth.bat"
> "%TMP_AUTH%" echo @echo off
>> "%TMP_AUTH%" echo chcp 65001 ^>nul
>> "%TMP_AUTH%" echo cd /d "%EAISELP_HOME%"
>> "%TMP_AUTH%" echo set "JWT_SECRET=%JWT_SECRET%"
>> "%TMP_AUTH%" echo set "GLM_API_KEY=%GLM_API_KEY%"
>> "%TMP_AUTH%" echo echo [auth] 启动中... 端口 8085
>> "%TMP_AUTH%" echo java -jar "%AUTH_JAR%"
>> "%TMP_AUTH%" echo echo [auth] 已退出。按任意键关闭...
>> "%TMP_AUTH%" echo pause ^>nul
start "eaiselp-auth" "%TMP_AUTH%"

echo [公共] 等待 auth 启动（15秒）...
ping -n 16 127.0.0.1 >nul

echo [公共] 启动 runtime(8081)...
REM runtime 启动
set "TMP_RT=%TEMP%\eaiselp_start_runtime.bat"
> "%TMP_RT%" echo @echo off
>> "%TMP_RT%" echo chcp 65001 ^>nul
>> "%TMP_RT%" echo cd /d "%EAISELP_HOME%"
>> "%TMP_RT%" echo set "JWT_SECRET=%JWT_SECRET%"
>> "%TMP_RT%" echo set "GLM_API_KEY=%GLM_API_KEY%"
>> "%TMP_RT%" echo echo [runtime] 启动中... 端口 8081
>> "%TMP_RT%" echo java -jar "%RUNTIME_JAR%"
>> "%TMP_RT%" echo echo [runtime] 已退出。按任意键关闭...
>> "%TMP_RT%" echo pause ^>nul
start "eaiselp-runtime" "%TMP_RT%"

echo [公共] 服务启动命令已发出，等待就绪（10秒）...
ping -n 11 127.0.0.1 >nul
exit /b 0


REM =====================================================================
REM 验证登录
REM =====================================================================
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
echo ============================================
echo   浏览器访问前端（需先启动前端服务）:
echo     cd /d 你的前端目录
echo     python start-web.py
echo     http://localhost:8080/login.html
echo   账号: admin / admin123
echo ============================================
exit /b 0
