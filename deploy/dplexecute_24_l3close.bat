@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 一键部署脚本 #24 L3收口（GRC 风险合规 + 投资决策分析，PRJ-004 闭口）
REM 日期：2026-08-21 | case-20260821-L3收口
REM
REM 【本次交付】case-20260821-L3收口（L3 层角色平台承载闭环）
REM   ✅ F1 GRC 风险合规: 风险登记册（概率×影响=风险值/四档等级自动映射/
REM      状态机 open→mitigating→closed+回退）+ 合规检查（等保/ISO/GDPR/自定义,
REM      手动登记制）+ 风险看板（5×5 纯 CSS 热力图+格下钻+等级分布+高风险清单）
REM   ✅ F2 投资决策: 商业案例（ROI/回收期/RICE 服务端重算防伪造,金额
REM      DECIMAL(14,2) 上限校验）+ 状态机（approve 独立原子防 PM 自我批准）
REM      + 投资组合视图（排除 draft/rejected 口径）
REM   ✅ V7 迁移: 3 新表 + 10 权限原子（1071~1080）+ 33 授权行（2170~2202）
REM      纯 IF NOT EXISTS 零 ALTER（真库验证 V1→V7 链+幂等重放过）
REM   ✅ 门禁: Reviewer 二审 PASS（D1 description 贯通修复）/ Security PASS
REM      （0 高危, S1 金额上限已修）/ QA 629 绿（+117 用例, D-QA1 T16 已修）
REM   ✅ 前端: risk-board/compliance-check-list/business-case-list 三新页
REM      + strategy-board 关联案例只读区（T16）
REM
REM 【MySQL 自适应】Docker 优先（测试机路线），不可用自动切原生 MySQL
REM
REM 环境变量（全部可选）：
REM   set GIT_REMOTE_URL=xxx       Git远程仓库（不配=只本地commit）
REM   set CICD_WEBHOOK_URL=xxx     CI/CD Webhook（不配=跳过触发）
REM   set DINGTALK_WEBHOOK=xxx     钉钉群机器人（不配=不推送）
REM   ★ GLM_API_KEY / JAVA_HOME 已内置为测试机固定参数（用户要求）
REM =====================================================================

echo ═══════════════════════════════════════════════════════
echo   eAISEDP 部署 #24 L3收口（风险合规/投资决策/看板）
echo   %date% %time%
echo ═══════════════════════════════════════════════════════
echo.

REM ============ 配置区 ============
set "PLATFORM_DIR=D:\eaiselp\platform"
set "WEB_DIR=D:\eaiselp\web"
set "AGENTS_DIR=D:\eaiselp\agents-config"
set "MYSQL_CONTAINER=eaiselp-mysql"
set "MYSQL_ROOT_PWD=root"
REM 原生 MySQL（Docker 不可用时；先用 deploy\setup_mysql_native.bat 搭建）
set "MYSQL_NATIVE_HOME=D:\eaiselp\mysql-8.0"
REM 测试机固定参数（用户要求内置 2026-08-18）
set "JAVA_HOME=D:\jdk-17\jdk-17.0.19+10"
set "GLM_API_KEY=3f3582bb3f2243fba844dea90cd2a75b.s7J8gzxCxCvbEw1U"
REM =================================

set "JWT_SECRET=dev-placeholder-secret-must-be-at-least-32-bytes-long-for-hs256-algorithm"
set "MYSQL_HOST=127.0.0.1"
set "MYSQL_PORT=3306"

REM ============ Step 1: 停旧服务 ============
echo [1/8] 停止旧服务...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8085 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
ping -n 4 127.0.0.1 >nul
echo   √

REM ============ Step 2: 拉代码 ============
echo [2/8] 拉取最新代码...
cd /d "%PLATFORM_DIR%" & git pull origin main 2>nul
cd /d "%WEB_DIR%" & git pull origin main 2>nul
if exist "%AGENTS_DIR%" (
    cd /d "%AGENTS_DIR%" & git pull origin main 2>nul
    echo   √ 三仓库已更新
) else (
    echo   ⚠ %AGENTS_DIR% 不存在（智能编排降级为固定6步）
)

REM ============ Step 3: 编译后端 ============
echo [3/8] 编译后端（1-2 分钟）...
cd /d "%PLATFORM_DIR%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call mvn clean package -DskipTests -q
if errorlevel 1 ( echo   × 编译失败！ & pause & exit /b 1 )
echo   √

REM ============ Step 4: MySQL 探测（docker / 原生自适应） ============
echo [4/8] MySQL 检查（自适应 docker/原生）...
set "MYSQL_MODE="
docker exec %MYSQL_CONTAINER% mysql -uroot -p%MYSQL_ROOT_PWD% -e "SELECT 1" >nul 2>nul
if not errorlevel 1 (
    set "MYSQL_MODE=docker"
    echo   √ Docker 容器 %MYSQL_CONTAINER% 可用
) else (
    set "MYSQL_CLI=%MYSQL_NATIVE_HOME%\bin\mysql.exe"
    if exist "!MYSQL_CLI!" (
        "!MYSQL_CLI!" -h%MYSQL_HOST% -P%MYSQL_PORT% -uroot -p%MYSQL_ROOT_PWD% -e "SELECT 1" >nul 2>nul
        if errorlevel 1 (
            echo   × 原生 MySQL 已安装但连不上——检查服务: net start eaiselp-mysql
            pause & exit /b 1
        )
        set "MYSQL_MODE=native"
        echo   √ 原生 MySQL 可用（%MYSQL_NATIVE_HOME%）
    ) else (
        echo   × Docker 不可用且原生 MySQL 未安装！
        echo     处理: 先运行 deploy\setup_mysql_native.bat（一次性搭建）
        echo     或安装 Docker Desktop 后重跑本脚本
        pause & exit /b 1
    )
)
if "!MYSQL_MODE!"=="docker" ( set "SQL_EXEC=docker exec %MYSQL_CONTAINER% mysql -uroot -p%MYSQL_ROOT_PWD% eaiselp -e" ) else ( set "SQL_EXEC="!MYSQL_CLI!" -h%MYSQL_HOST% -P%MYSQL_PORT% -uroot -p%MYSQL_ROOT_PWD% eaiselp -e" )
echo   模式: !MYSQL_MODE!

REM ============ Step 5: 数据库准备（清失败记录，V6 启动时自动迁移） ============
echo [5/8] 数据库准备...
REM 注：库由 setup_mysql_native.bat / docker 预建（老环境已存在），此处只清 Flyway 失败记录
%SQL_EXEC% "DELETE FROM flyway_schema_history WHERE success = 0;" >nul 2>nul
%SQL_EXEC% "DELETE FROM flyway_schema_history WHERE version='6' AND success=0;" >nul 2>nul
echo   √ 失败迁移记录已清理（V6 幂等，启动时自动收敛；重放 WARN 1050/1062 无害）

REM ============ Step 6: 启动 auth ============
echo [6/8] 启动后端...
set "TMP_AUTH=%TEMP%\eaiselp_start_auth.bat"
> "%TMP_AUTH%" echo @echo off
>> "%TMP_AUTH%" echo chcp 65001 ^>nul
>> "%TMP_AUTH%" echo cd /d "%PLATFORM_DIR%"
>> "%TMP_AUTH%" echo set "JWT_SECRET=%JWT_SECRET%"
>> "%TMP_AUTH%" echo set "GLM_API_KEY=%GLM_API_KEY%"
>> "%TMP_AUTH%" echo set "MYSQL_HOST=%MYSQL_HOST%"
>> "%TMP_AUTH%" echo set "MYSQL_PASSWORD=%MYSQL_ROOT_PWD%"
>> "%TMP_AUTH%" echo echo === eAISEDP Auth 启动中... 8085 ===
>> "%TMP_AUTH%" echo java -jar eaiselp-auth\target\eaiselp-auth.jar
>> "%TMP_AUTH%" echo echo === Auth 已停止 ===
>> "%TMP_AUTH%" echo pause ^>nul
start "eaiselp-auth" "%TMP_AUTH%"
echo   等 auth 启动（20秒）...
ping -n 21 127.0.0.1 >nul

REM ============ Step 7: 启动 runtime ============
set "TMP_RT=%TEMP%\eaiselp_start_runtime.bat"
> "%TMP_RT%" echo @echo off
>> "%TMP_RT%" echo chcp 65001 ^>nul
>> "%TMP_RT%" echo cd /d "%PLATFORM_DIR%"
>> "%TMP_RT%" echo set "JWT_SECRET=%JWT_SECRET%"
>> "%TMP_RT%" echo set "GLM_API_KEY=%GLM_API_KEY%"
>> "%TMP_RT%" echo set "MYSQL_HOST=%MYSQL_HOST%"
>> "%TMP_RT%" echo set "MYSQL_PASSWORD=%MYSQL_ROOT_PWD%"
>> "%TMP_RT%" echo set "SYSTEM_PATH=%AGENTS_DIR%"
if defined GIT_REMOTE_URL ( >> "%TMP_RT%" echo set "GIT_REMOTE_URL=%GIT_REMOTE_URL%" )
if defined GIT_TOKEN ( >> "%TMP_RT%" echo set "GIT_TOKEN=%GIT_TOKEN%" )
if defined CICD_WEBHOOK_URL ( >> "%TMP_RT%" echo set "CICD_WEBHOOK_URL=%CICD_WEBHOOK_URL%" )
if defined CICD_WEBHOOK_TOKEN ( >> "%TMP_RT%" echo set "CICD_WEBHOOK_TOKEN=%CICD_WEBHOOK_TOKEN%" )
if defined DINGTALK_WEBHOOK ( >> "%TMP_RT%" echo set "DINGTALK_WEBHOOK=%DINGTALK_WEBHOOK%" )
>> "%TMP_RT%" echo echo === eAISEDP Runtime 启动中... 8081 ===
>> "%TMP_RT%" echo echo === 日志关注: Flyway V6 迁移 / governance 四域加载 ===
>> "%TMP_RT%" echo java -jar eaiselp-runtime\target\eaiselp-runtime.jar
>> "%TMP_RT%" echo echo === Runtime 已停止 ===
>> "%TMP_RT%" echo pause ^>nul
start "eaiselp-runtime" "%TMP_RT%"
echo   等 runtime 启动（20秒）...
ping -n 21 127.0.0.1 >nul
echo   √

REM ============ Step 8: 启动前端 ============
echo [7/8] 启动前端...
cd /d "%WEB_DIR%"
start "eaiselp-web" cmd /k "cd /d %WEB_DIR% && python start-web.py"
ping -n 4 127.0.0.1 >nul
echo   √

REM ============ 验证 ============
echo [8/8] 验证...
echo.
echo   auth:    & curl -s --connect-timeout 5 http://localhost:8085/actuator/health
echo.
echo   runtime: & curl -s --connect-timeout 5 http://localhost:8081/actuator/health
echo.
echo   Flyway 迁移历史（预期记录全 success=1，V6= l2 governance close）:
%SQL_EXEC% "SELECT version,success FROM flyway_schema_history ORDER BY installed_rank;"
echo.

echo ═══════════════════════════════════════════════════════
echo   部署完成！
echo ═══════════════════════════════════════════════════════
echo.
echo   访问: http://localhost:8080/login.html
echo   账号: admin / admin123
echo.
echo   #24 L3收口验证 checklist:
echo   ─────────────────────────────────────────
echo   0. XSS 加固（本次核心）: 标准正文/模板正文粘贴攻击样例——
echo      ^<img src=x onerror=alert(1)^> / 嵌套 ^<scr^<script^>ipt^> /
echo      href="java&#9;script:..."（中缀TAB）/ ^<base href^> / ^<svg onload^> /
echo      style="position:fixed" → 详情渲染全部被清洗（无弹窗/无劫持/无样式）；
echo      正常 markdown（链接/图片 data:image/png）不受影响
echo   ─────────────────────────────────────────
echo   既有功能回归 checklist（#22/#23 应全部不变）:
echo   ─────────────────────────────────────────
echo   1. 标准库: 菜单'标准库'（tenant_admin）→ 新建标准（draft）→
echo      发布 → 升版 v2.0 再发布 → v1.0 自动 deprecated（原因含"被取代"）
echo      → 标准关联门禁规则 → Case 编排被该门禁打回时详情显示
echo      "依据标准：{code}《{title}》{version}"
echo   2. 模板库: 菜单'模板库' → 新建（PRD/技术方案等类型，支持自定义）→
echo      编辑必须换版本号 → 停用后默认列表隐藏
echo   3. 数据资产: 菜单'数据资产' → 登记（类型×敏感四档）→
echo      同系统同名拒/跨系统同名过 → 删除资产关联规则联动删
echo   4. 质量规则: 菜单'质量规则' → 新建（挂资产，阈值0~100）→
echo      登记检查结果（覆盖式，历史走审计）
echo   5. 到期拦截: 把某测试租户 expire_time 改为过去 →
echo      登录报"试用已到期"（40003）→ 改未来7天内 → 登录提示条三档
echo      → runtime 侧 /derive /orchestrate /retry 三入口同样拦截
echo      → platform_admin 可经 U2 恢复（见 docs\运维文档\试用到期恢复runbook.md）
echo   6. RBAC: engineer 登录四域只读可见（写按钮隐藏+后端403兜底）
echo   ─────────────────────────────────────────
echo   既有功能回归 checklist:
echo   ─────────────────────────────────────────
echo   1. 三层贯通: 战略→项目群→项目→Case 建链/[Inject]注入/进度汇总
echo   2. L2治理: DORA看板/里程碑/依赖环检测/ADR/技术雷达
echo   3. 核心闭环: 一键编排/审批锁/工作区/断点续跑/模型路由/配额
echo   4. 商用: 注册试用（30天）/自配Key/报表
echo   ─────────────────────────────────────────
echo.
if "%GLM_API_KEY%"=="" (
    echo   ⚠⚠ GLM_API_KEY 未设置！所有编排/派生会失败！
    echo.
)
pause
