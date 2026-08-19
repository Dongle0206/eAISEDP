@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISEDP 一键部署脚本 #20 L2治理核心（基于 #19 三层贯通，新增治理+知识资产）
REM 日期：2026-08-19
REM
REM 本脚本在 #19（三层贯通 L3/L2/L1）基础上新增：
REM   ✅ DORA 效能看板: 四指标实时聚合（5min TTL）+ 三级维度钻取 + 口径 tooltip
REM   ✅ 里程碑激活: V1 死表复活（两级归属+状态机+防挪窝+达成确认）
REM   ✅ 依赖管理: blocks 归一化 + 三色 DFS 环检测（400 带路径）+ blocked 看板
REM   ✅ ADR 库: 五段式 + 状态机 + superseded 链防环 + 架构原则联动反查
REM   ✅ 技术雷达: 四象限 + 环分组 + 180 天待复审（纯 CSS 离线渲染）
REM   ✅ V5 迁移: 三新表+埋点列+权限 seed（Flyway 启动时自动执行）
REM      t_project_dependency / t_adr / t_tech_radar_item
REM      + t_project 里程碑激活列 + t_derivation 埋点列
REM
REM 环境变量（全部可选，不配也能用核心功能）：
REM   set GIT_REMOTE_URL=xxx       Git远程仓库（不配=只本地commit）
REM   set GIT_TOKEN=xxx            Git访问token
REM   set CICD_WEBHOOK_URL=xxx     CI/CD Webhook（不配=跳过触发）
REM   set CICD_WEBHOOK_TOKEN=xxx   Webhook认证
REM   set DINGTALK_WEBHOOK=xxx     钉钉群机器人（不配=不推送）
REM   ★ GLM_API_KEY / JAVA_HOME 已内置为测试机固定参数（用户要求）
REM =====================================================================

echo ═══════════════════════════════════════════════════════
echo   eAISEDP L2治理核心部署 #20（DORA/里程碑/依赖/ADR/技术雷达）
echo   %date% %time%
echo ═══════════════════════════════════════════════════════
echo.

REM ============ 配置区 ============
set "PLATFORM_DIR=D:\eaiselp\platform"
set "WEB_DIR=D:\eaiselp\web"
set "AGENTS_DIR=D:\eaiselp\agents-config"
set "MYSQL_CONTAINER=eaiselp-mysql"
set "MYSQL_ROOT_PWD=root"
REM 测试机固定参数（用户要求内置 2026-08-18）
set "JAVA_HOME=D:\jdk-17\jdk-17.0.19+10"
set "GLM_API_KEY=3f3582bb3f2243fba844dea90cd2a75b.s7J8gzxCXCvbEw1U"
REM 可选配置（取消注释启用）：
REM set "GIT_REMOTE_URL=https://your-gitlab.com/your-repo.git"
REM set "GIT_TOKEN=your_git_token"
REM set "CICD_WEBHOOK_URL=http://your-jenkins/generic-webhook-trigger/invoke"
REM set "CICD_WEBHOOK_TOKEN=your_webhook_token"
REM set "DINGTALK_WEBHOOK=https://oapi.dingtalk.com/robot/send?access_token=xxx"
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
                if exist "%%j\bin\java.exe" ( set "JAVA_HOME=%%j" & set "JDK_FOUND=1" )
            )
        )
    )
    if not defined JDK_FOUND (
        echo [JDK] × 未找到 JDK17！
        echo   安装: winget install EclipseAdoptium.Temurin.17.JDK
        echo   或编辑本脚本配置区的 JAVA_HOME
        pause & exit /b 1
    )
    echo [JDK] !JAVA_HOME!
)
echo [配置] 后端: %PLATFORM_DIR%
echo [配置] 前端: %WEB_DIR%
echo [配置] 体系: %AGENTS_DIR%
echo [配置] JDK: %JAVA_HOME%
if defined GIT_REMOTE_URL ( echo [配置] Git 远程: %GIT_REMOTE_URL% ) else ( echo [配置] Git 远程: 未配置（只本地 commit） )
if defined CICD_WEBHOOK_URL ( echo [配置] CI/CD: %CICD_WEBHOOK_URL% ) else ( echo [配置] CI/CD: 未配置（跳过触发） )
if defined DINGTALK_WEBHOOK ( echo [配置] 钉钉: 已配置 ) else ( echo [配置] 钉钉: 未配置 )
if "%GLM_API_KEY%"=="" ( echo [配置] ⚠ GLM_API_KEY 未设置！编排会失败 )
echo.

REM ============ Step 1: 停旧服务 ============
echo [1/7] 停止旧服务...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8085 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8081 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul
ping -n 4 127.0.0.1 >nul
echo   √

REM ============ Step 2: 拉代码（三个仓库） ============
echo [2/7] 拉取最新代码（后端+前端+体系配置）...
cd /d "%PLATFORM_DIR%" & git pull origin main 2>nul
cd /d "%WEB_DIR%" & git pull origin main 2>nul
if exist "%AGENTS_DIR%" (
    cd /d "%AGENTS_DIR%" & git pull origin main 2>nul
    echo   √ 三仓库已更新
) else (
    echo   ⚠ %AGENTS_DIR% 不存在！智能编排将降级为固定6步
    echo     如需智能编排: git clone https://github.com/Dongle0206/eAISEDP-system.git %AGENTS_DIR%
)

REM ============ Step 3: 编译后端 ============
echo [3/7] 编译后端（1-2 分钟）...
cd /d "%PLATFORM_DIR%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call mvn clean package -DskipTests -q
if errorlevel 1 ( echo   × 编译失败！ & pause & exit /b 1 )
echo   √
echo.
echo   [Flyway] 启动时自动执行 V5（三新表+埋点列+权限seed）:
echo     t_project_dependency / t_adr / t_tech_radar_item
echo     + t_project 里程碑激活列 + t_derivation 埋点列 + 种子数据
echo.

REM ============ Step 4: 数据库检查（Flyway 自动迁移 V5） ============
echo [4/7] 数据库检查...
docker exec %MYSQL_CONTAINER% mysql -uroot -p%MYSQL_ROOT_PWD% -e "SELECT 1" >nul 2>nul
if errorlevel 1 (
    echo   × MySQL 容器未运行！启动: docker start %MYSQL_CONTAINER%
    pause & exit /b 1
)
REM 清理 Flyway 失败的迁移记录（修复后重跑需要）
docker exec %MYSQL_CONTAINER% mysql -uroot -p%MYSQL_ROOT_PWD% eaiselp -e "DELETE FROM flyway_schema_history WHERE success = 0;" >nul 2>nul
REM V4 兜底: 部署机曾跑过手工版 V4 草案会留下 version=4 失败记录，阻塞正式 V4 迁移
docker exec %MYSQL_CONTAINER% mysql -uroot -p%MYSQL_ROOT_PWD% eaiselp -e "DELETE FROM flyway_schema_history WHERE version='4' AND success=0;" >nul 2>nul
REM V5 兜底: 若 V5 首次执行失败（如表结构冲突/权限seed半写）会留下 version=5 失败记录，阻塞重跑
REM 幂等无害——正常情况下删 0 行；V5 全部 CREATE TABLE IF NOT EXISTS，重跑安全
docker exec %MYSQL_CONTAINER% mysql -uroot -p%MYSQL_ROOT_PWD% eaiselp -e "DELETE FROM flyway_schema_history WHERE version='5' AND success=0;" >nul 2>nul
echo   √ MySQL 正常（已清理失败迁移记录含 V4/V5 兜底，Flyway 启动时自动迁移）

REM ============ Step 5: 启动 auth ============
echo [5/7] 启动后端...
set "TMP_AUTH=%TEMP%\eaiselp_start_auth.bat"
> "%TMP_AUTH%" echo @echo off
>> "%TMP_AUTH%" echo chcp 65001 ^>nul
>> "%TMP_AUTH%" echo cd /d "%PLATFORM_DIR%"
>> "%TMP_AUTH%" echo set "JWT_SECRET=%JWT_SECRET%"
>> "%TMP_AUTH%" echo set "GLM_API_KEY=%GLM_API_KEY%"
>> "%TMP_AUTH%" echo set "MYSQL_HOST=127.0.0.1"
>> "%TMP_AUTH%" echo set "MYSQL_PASSWORD=%MYSQL_ROOT_PWD%"
>> "%TMP_AUTH%" echo echo === eAISEDP Auth 启动中... 8085 ===
>> "%TMP_AUTH%" echo java -jar eaiselp-auth\target\eaiselp-auth.jar
>> "%TMP_AUTH%" echo echo === Auth 已停止 ===
>> "%TMP_AUTH%" echo pause ^>nul
start "eaiselp-auth" "%TMP_AUTH%"
echo   等 auth 启动（20秒）...
ping -n 21 127.0.0.1 >nul

REM ============ Step 6: 启动 runtime ============
set "TMP_RT=%TEMP%\eaiselp_start_runtime.bat"
> "%TMP_RT%" echo @echo off
>> "%TMP_RT%" echo chcp 65001 ^>nul
>> "%TMP_RT%" echo cd /d "%PLATFORM_DIR%"
>> "%TMP_RT%" echo set "JWT_SECRET=%JWT_SECRET%"
>> "%TMP_RT%" echo set "GLM_API_KEY=%GLM_API_KEY%"
>> "%TMP_RT%" echo set "MYSQL_HOST=127.0.0.1"
>> "%TMP_RT%" echo set "MYSQL_PASSWORD=%MYSQL_ROOT_PWD%"
>> "%TMP_RT%" echo set "SYSTEM_PATH=%AGENTS_DIR%"
if defined GIT_REMOTE_URL ( >> "%TMP_RT%" echo set "GIT_REMOTE_URL=%GIT_REMOTE_URL%" )
if defined GIT_TOKEN ( >> "%TMP_RT%" echo set "GIT_TOKEN=%GIT_TOKEN%" )
if defined CICD_WEBHOOK_URL ( >> "%TMP_RT%" echo set "CICD_WEBHOOK_URL=%CICD_WEBHOOK_URL%" )
if defined CICD_WEBHOOK_TOKEN ( >> "%TMP_RT%" echo set "CICD_WEBHOOK_TOKEN=%CICD_WEBHOOK_TOKEN%" )
if defined DINGTALK_WEBHOOK ( >> "%TMP_RT%" echo set "DINGTALK_WEBHOOK=%DINGTALK_WEBHOOK%" )
>> "%TMP_RT%" echo echo === eAISEDP Runtime 启动中... 8081 ===
>> "%TMP_RT%" echo echo === 日志关注: Flyway V5迁移/加载角色数/[Inject]注入行 ===
>> "%TMP_RT%" echo java -jar eaiselp-runtime\target\eaiselp-runtime.jar
>> "%TMP_RT%" echo echo === Runtime 已停止 ===
>> "%TMP_RT%" echo pause ^>nul
start "eaiselp-runtime" "%TMP_RT%"
echo   等 runtime 启动（20秒）...
ping -n 21 127.0.0.1 >nul
echo   √

REM ============ Step 7: 启动前端 ============
echo [6/7] 启动前端...
cd /d "%WEB_DIR%"
start "eaiselp-web" cmd /k "cd /d %WEB_DIR% && python start-web.py"
ping -n 4 127.0.0.1 >nul
echo   √

REM ============ 验证 ============
echo [7/7] 验证...
echo.
echo   auth:    & curl -s --connect-timeout 5 http://localhost:8085/actuator/health
echo.
echo   runtime: & curl -s --connect-timeout 5 http://localhost:8081/actuator/health
echo.
echo.

echo ═══════════════════════════════════════════════════════
echo   部署完成！
echo ═══════════════════════════════════════════════════════
echo.
echo   访问: http://localhost:8080/login.html
echo   账号: admin / admin123
echo   新用户: 登录页点'注册企业试用（30天）'自助开通
echo.
echo   L2治理核心验证 checklist（本次新增 5 项）:
echo   ─────────────────────────────────────────
echo   1. DORA看板: 菜单'效能看板'（tenant_admin） → 三级维度（项目群/项目/Case）
echo      切换 → 四指标卡片（部署频率/前置时间/打回率/恢复时间）+ 指标旁
echo      '?'悬停看口径 tooltip（P50插值/P90序位/两源防双计说明）
echo   2. 里程碑: 项目群/项目详情 → 里程碑时间线 → 新建里程碑
echo      （两级归属+状态机 planned→in_progress→achieved）
echo      → 挪到别的项目下保存应被拒（防挪窝） → 点'达成确认'
echo      → achievementHint 展示达成证据
echo   3. 依赖: 菜单'依赖管理' → 添加 A blocks B 依赖 → 再加 B blocks A
echo      成环 → 应 400 且错误信息带环路径 → 删除依赖后 blocked
echo      看板恢复，重新添加显示复活语义
echo   4. ADR: 菜单'ADR 库'（全角色可见） → 新建 ADR（五段式表单） →
echo      状态流转 proposed→accepted → 再建新 ADR supersede 旧的 →
echo      挂过架构原则的 ADR 在原则详情可反查到
echo   5. 雷达: 菜单'技术雷达'（全角色可见） → 四象限
echo      （Adopt/Trial/Assess/Hold）+ 环分组 → 180 天未复审条目
echo      标'待复审' → 断网刷新仍可渲染（纯CSS离线）
echo   ─────────────────────────────────────────
echo   三层贯通验证 checklist（#19 遗留）:
echo   ─────────────────────────────────────────
echo   1. 菜单: admin(executive) 登录 → 有'战略管理/分层设置'
echo      tenant_admin 登录 → 有'项目群/项目/架构原则/门禁规则'
echo      （layers 关闭 L3/L2 时菜单自动隐藏——场景C 仅 L1；
echo       ADR库/技术雷达是租户知识资产，任何开关组合下不隐藏）
echo   2. 建链: 战略管理→建战略 → 项目群→建项目群(挂战略)
echo      → 项目→建项目(挂项目群) → Case→挂接项目
echo   3. 注入: 挂接项目的 Case 编排 → runtime 窗口日志看 [Inject] 行
echo      （架构原则/项目上下文注入编排）
echo   4. 汇总: Case 完成(done) → 项目详情进度自动更新
echo      （Spring 事件→异步全量重算）
echo   5. 门禁: 门禁规则页可配置（原 GATE_ROLES 硬编码已升级等价）
echo   ─────────────────────────────────────────
echo   原有核心功能（#13-#17）验证 checklist:
echo   ─────────────────────────────────────────
echo   1. 一键编排: Case详情 → 选档位 → 输入需求 → 编排
echo   2. 审批锁: 跑到'运维'步骤前暂停 → 去检查点审批
echo   3. 工作区: 编排完 → '工作区文件' → 验证代码/预览网页
echo   4. 断点续跑: 失败编排 → '重试失败步骤'
echo   5. 状态流转: Case详情 → 流转按钮
echo   6. 管理闭环: 模型路由在线换模型/配额调整/角色管理
echo   7. 租户: LLM Key配置/统计报表
echo   8. 注册: 登录页 → 注册企业 → 新账号登录
echo   ─────────────────────────────────────────
echo.
echo   产出落地验证:
echo     dir /s /b %PLATFORM_DIR%\workspaces\
echo     cd %PLATFORM_DIR%\workspaces\{caseId} ^&^& git log
echo.
if "%GLM_API_KEY%"=="" (
    echo   ⚠⚠ GLM_API_KEY 未设置！所有编排/派生会失败！
    echo      set GLM_API_KEY=你的智谱Key 后重新运行本脚本
    echo.
)
pause
