@echo off
chcp 65001 >nul

REM =====================================================================
REM eAISEDP 前端 nginx 代理修复 v2
REM 日期：2026-07-24
REM 修复：host.docker.internal 在 WSL2 下不通，改用宿主机内网 IP
REM 双击即可执行
REM =====================================================================

echo ============================================
echo   前端 nginx 代理修复 v2
echo   %date% %time%
echo ============================================
echo.

REM 停旧 nginx
docker rm -f eaiselp-web 2>nul
ping -n 3 127.0.0.1 >nul

REM 获取本机 IP（取 172 开头的第一个）
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr "172\."') do (
    for /f "tokens=1" %%b in ("%%a") do (
        set HOST_IP=%%b
        goto gotip
    )
)
:gotip
if "%HOST_IP%"=="" set HOST_IP=172.16.180.166
echo [INFO] 宿主机 IP: %HOST_IP%
echo.

REM 确认 auth 和 runtime 在监听
echo [检查] auth(8085) 监听状态:
netstat -ano | findstr :8085 | findstr LISTENING
echo [检查] runtime(8081) 监听状态:
netstat -ano | findstr :8081 | findstr LISTENING
echo.

REM 写 nginx 配置（用宿主机 IP）
echo [1/3] 创建 nginx 配置（代理到 %HOST_IP%）...
if not exist D:\eaiselp\nginx-conf mkdir D:\eaiselp\nginx-conf
(
echo server {
echo     listen 80;
echo     server_name localhost;
echo     root /usr/share/nginx/html;
echo     index login.html;
echo.
echo     location / {
echo         try_files $uri $uri/ /login.html;
echo     }
echo.
echo     location /api/v1/auth/ {
echo         proxy_pass http://%HOST_IP%:8085;
echo         proxy_set_header Host $host;
echo         proxy_set_header X-Real-IP $remote_addr;
echo         proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
echo         proxy_connect_timeout 10s;
echo         proxy_read_timeout 60s;
echo     }
echo.
echo     location /api/ {
echo         proxy_pass http://%HOST_IP%:8081;
echo         proxy_set_header Host $host;
echo         proxy_set_header X-Real-IP $remote_addr;
echo         proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
echo         proxy_connect_timeout 10s;
echo         proxy_read_timeout 60s;
echo     }
echo }
) > D:\eaiselp\nginx-conf\default.conf
echo   [OK]
echo.

REM 更新前端 config.js（同源）
echo [2/3] 更新 config.js（同源）...
(
echo window.EAISELP_CONFIG = {
echo   AUTH_BASE_URL: '',
echo   API_BASE_URL: '',
echo   TOKEN_KEY: 'eaiselp_token',
echo   LOGIN_PAGE: 'login.html',
echo   INDEX_PAGE: 'index.html'
echo };
) > D:\eaiselp\web\config.js
echo   [OK]
echo.

REM 启动 nginx
echo [3/3] 启动 nginx...
docker run -d ^
  --name eaiselp-web ^
  --restart unless-stopped ^
  -p 80:80 ^
  -v D:\eaiselp\web:/usr/share/nginx/html:ro ^
  -v D:\eaiselp\nginx-conf\default.conf:/etc/nginx/conf.d/default.conf:ro ^
  --add-host=host.docker.internal:host-gateway ^
  nginx:alpine

ping -n 4 127.0.0.1 >nul

REM 验证 nginx 能代理到 auth
echo.
echo [验证] nginx 代理测试:
curl -s -o nul -w "login.html HTTP %%{http_code}\n" http://localhost/login.html
curl -s -o nul -w "auth API HTTP %%{http_code}\n" -X POST http://localhost/api/v1/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123\"}"
echo.

echo ============================================
echo   完成！浏览器访问: http://localhost/login.html
echo   账号: admin / admin123
echo ============================================
pause
