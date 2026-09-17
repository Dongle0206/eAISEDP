@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 一键部署脚本 #27 技术债清偿（case-20260824，fast 档：安全加固+审计补强）
REM 日期：2026-09-16
REM
REM 【本次交付】case-20260823-商用化（PRJ-006 闭口——PRG-001 全部子项目交付）
REM   ? F1 订阅计划: 套餐定义(3 seed: starter 299/pro 999/enterprise 4999,
REM      功能开关 JSON/SLA 等级/custom 档位覆写) + U2 扩展五类一事务
REM      (edition/expire/plan_code/配额模板/层开关) + 套餐管理页
REM   ? F2 计量账单: 月度定时出账(每月1日02:00,uk幂等) + 超量计费(千token
REM      ceil×单价) + 首月按天折算(勘误v1.1) + 三态流转 draft→issued→paid
REM      + 手动补生成 + 费用中心(四维水位/SLA卡/历史账单)
REM   ? F3 SLA: 套餐等级承诺展示(bronze/silver/gold) + 事故登记
REM   ? V8 迁移: t_plan/t_invoice/t_incident + t_tenant.plan_code 动态加列
REM      + 9 权限原子(1081~1089) + 18 授权行(2203~2220), 真库验证过
REM   ? 门禁: Security PASS(资金面通过) / Reviewer 二审 PASS(D1 漏列+D2
REM      收口测试修复) / QA 27/27 AC(105 自动化用例,750 测试绿)
REM   ? 前端: plan-list/tenant-list(订阅变更)/invoice-list/cost-center/
REM      incident-list 五新页 + dict 5 组 + menu 挂载
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
echo   eAISEDP 部署 #27 技术债清偿（审计/CAS/安全根除/输入校验）
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
echo   #27 技术债清偿验证 checklist:
echo   ─────────────────────────────────────────
echo   0. 安全(T12): 登录后带伪造 X-Tenant-Id: 999 头调任意
echo      API → 数据仍为本租户(头已无效化,租户仅 JWT 派生)
echo   1. 审计(T1/T11): 编辑风险 P/I → 审计日志含 old→new;
echo      账单流转的审计与 UPDATE 同事务(失败即回滚)
echo   2. CAS(T2/T6): 两窗口同开一个风险/标准 → 同发流转
echo      → 后提交者 400"状态已变更"
echo   3. 输入(T4/T5/T9): 超长字段 400 指名; 坏 JSON 400
echo      非 50000; MCP 超大参数 -32602
echo   4. 回归: 全部既有功能(三层/L2/L3/商用化/MCP)不变
echo   ─────────────────────────────────────────
echo   既有功能回归 checklist:
echo   ─────────────────────────────────────────
echo   0. 套餐: 菜单'套餐管理'(仅 platform_admin) → 3 套餐 seed
echo      (starter 299/pro 999/enterprise 4999) → 编辑/上下架
echo   1. 订阅变更: 菜单'租户管理'(PA) → 选租户'订阅变更' →
echo      选 pro 套餐提交 → U1 快照回显(edition=pro/planName/SLA) →
echo      该租户配额/层开关同步生效(五类一事务)
echo   2. 账单: 菜单'账单管理'(PA) → '补生成'指定账期 →
echo      明细含套餐快照/用量/超量/折算(首月按天) →
echo      draft→issued→paid 流转 → 每月1日 02:00 定时自动出账
echo   3. 费用中心: tenant_admin 登录 → 本期用量四维水位 +
echo      SLA 承诺卡 + 历史账单 + 超量明细
echo   4. 事故: 菜单'事故登记' → 登记(时间/影响/根因/sla_breach)
echo   5. 口径抽验: pro 租户用量 6,001,500 token(配额 5M/单价8元) →
echo      账单应缴 9,015.00(月价999+ceil 1002千×8)
echo   ─────────────────────────────────────────
echo   既有功能回归 checklist:
echo   ─────────────────────────────────────────
echo   ⚠ 升级注记(Reviewer D1): 既有 MCP_ENABLED=true 且未配
echo      MCP_PROVIDER 的部署升级后默认切到 mock——保留 http 须
echo      显式设 MCP_PROVIDER=http（mock 响应带 __mock:true 自标识）
echo   0. mcp.html: 目录三源分组(9工具) → 选工具 schema 动态表单 →
echo      试调用 → 结果转义展示 + 展开"原始报文"折叠区
echo   1. 隔离: 两租户各建 issue → 互查对方 id 应 NOT_FOUND
echo   2. 限流: 连续 invoke 超 30次/分 → 429
echo   3. fail-closed: MCP_PROVIDER=非法值 → 降级"未配置"不 500
echo   ─────────────────────────────────────────
echo   既有功能回归 checklist:
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
