@echo off
chcp 65001 >nul

REM =====================================================================
REM eAISEDP 前端 nginx 修复（消除跨域——nginx 代理 API 请求）
REM 日期：2026-07-24
REM 说明：前端 localhost:80 直接调 auth:8085/runtime:8081 有跨域问题，
REM       改用 nginx 反向代理 /api/auth/* → 8085, /api/* → 8081,
REM       前端不再跨域，config.js 改为同源空 base-url。
REM 双击即可执行
REM =====================================================================

echo ============================================
echo   eAISEDP 前端 nginx 跨域修复
echo   %date% %time%
echo ============================================
echo.

REM 停旧 nginx
echo [1/4] 停旧 nginx 容器...
docker rm -f eaiselp-web 2>nul
echo   [OK]
echo.

REM 创建 nginx 配置
echo [2/4] 创建 nginx 反向代理配置...
if not exist D:\eaiselp\nginx-conf mkdir D:\eaiselp\nginx-conf
(
echo server {
echo     listen 80;
echo     server_name localhost;
echo     root /usr/share/nginx/html;
echo     index login.html;
echo.
echo     # 前端静态文件
echo     location / {
echo         try_files $uri $uri/ /login.html;
echo     }
echo.
echo     # auth 服务代理（登录/当前用户/退出）
echo     location /api/v1/auth/ {
echo         proxy_pass http://host.docker.internal:8085;
echo         proxy_set_header Host $host;
echo         proxy_set_header X-Real-IP $remote_addr;
echo     }
echo.
echo     # runtime 业务 API 代理
echo     location /api/ {
echo         proxy_pass http://host.docker.internal:8081;
echo         proxy_set_header Host $host;
echo         proxy_set_header X-Real-IP $remote_addr;
echo     }
echo }
) > D:\eaiselp\nginx-conf\default.conf
echo   [OK] 配置已写入 D:\eaiselp\nginx-conf\default.conf
echo.

REM 更新前端 config.js（改为同源，消除跨域）
echo [3/4] 更新前端 config.js（改为同源访问）...
(
echo /**
echo  * eAISEDP 前端配置（nginx 同源模式，无跨域）
echo  */
echo window.EAISELP_CONFIG = {
echo   AUTH_BASE_URL: '',
echo   API_BASE_URL: '',
echo   TOKEN_KEY: 'eaiselp_token',
echo   LOGIN_PAGE: 'login.html',
echo   INDEX_PAGE: 'index.html'
echo };
) > D:\eaiselp\web\config.js
echo   [OK] config.js 已更新为同源
echo.

REM 启动新 nginx（挂载前端目录 + 自定义配置）
echo [4/4] 启动 nginx（带反向代理）...
docker run -d ^
  --name eaiselp-web ^
  --restart unless-stopped ^
  -p 80:80 ^
  -v D:\eaiselp\web:/usr/share/nginx/html:ro ^
  -v D:\eaiselp\nginx-conf\default.conf:/etc/nginx/conf.d/default.conf:ro ^
  nginx:alpine

if errorlevel 1 (
    echo   [错误] nginx 启动失败
    pause
    exit /b 1
)

ping -n 4 127.0.0.1 >nul
echo   [OK] nginx 已启动
echo.

echo ============================================
echo   修复完成！
echo.
echo   浏览器访问: http://localhost/login.html
echo   或:         http://172.16.180.166/login.html
echo   账号: admin / admin123
echo.
echo   原理：nginx 统一入口 80 端口
echo     /api/v1/auth/* → 代理到 auth(8085)
echo     /api/*         → 代理到 runtime(8081)
echo     其他           → 前端静态文件
echo   前端不再跨域，config.js base-url 为空（同源）
echo ============================================
pause
