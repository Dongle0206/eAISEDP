@echo off
chcp 65001 >nul

REM =====================================================================
REM eAISEDP 前端部署（一次性）
REM 日期：2026-07-24
REM 说明：用 Docker nginx 容器托管前端静态文件
REM 前置：新机已装 Docker，前端代码已 clone 到 D:\eaiselp\web
REM 双击即可执行
REM =====================================================================

echo ============================================
echo   eAISEDP 前端部署（nginx 容器）
echo   %date% %time%
echo ============================================
echo.

REM 检查前端目录
if not exist D:\eaiselp\web\login.html (
    echo [错误] 前端代码不存在: D:\eaiselp\web\login.html
    echo 请先 clone 前端仓:
    echo   cd /d D:\eaiselp
    echo   git clone https://github.com/Dongle0206/eAISEDP-web.git web
    pause
    exit /b 1
)

REM 停旧 nginx 容器（如有）
echo [1/3] 清理旧前端容器...
docker rm -f eaiselp-web 2>nul
echo   [OK]
echo.

REM 启动 nginx 容器
echo [2/3] 启动 nginx 容器（端口 80）...
docker run -d ^
  --name eaiselp-web ^
  --restart unless-stopped ^
  -p 80:80 ^
  -v D:\eaiselp\web:/usr/share/nginx/html:ro ^
  nginx:alpine

if errorlevel 1 (
    echo   [错误] nginx 容器启动失败
    pause
    exit /b 1
)
echo   [OK] nginx 已启动
echo.

REM 等待 nginx 就绪
ping -n 4 127.0.0.1 >nul

REM 验证
echo [3/3] 验证前端访问...
curl -s -o nul -w "HTTP %%{http_code}" http://localhost/login.html
echo.
echo.

echo ============================================
echo   前端部署完成！
echo.
echo   本机访问:  http://localhost/login.html
echo   跨机访问:  http://172.16.180.166/login.html
echo   账号: admin / admin123
echo.
echo   注意:
echo   - config.js 已配好 API 地址(172.16.180.166:8085/8081)
echo   - 前端更新: cd D:\eaiselp\web ^&^& git pull
echo   - nginx 自动重启(docker restart unless-stopped)
echo ============================================
pause
