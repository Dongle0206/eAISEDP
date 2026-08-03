@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM eAISELP MySQL backup script
REM Dumps the eaiselp database via docker exec mysqldump, then prunes
REM backups older than 7 days with forfiles.
REM
REM Usage:
REM   backup-db.bat
REM
REM Config (override via env or edit below):
REM   CONTAINER  mysql docker container name (default: eaiselp-mysql)
REM   DB_NAME    database name           (default: eaiselp)
REM   DB_USER    mysql user              (default: root)
REM   DB_PASS    mysql password          (default: root)
REM   BACKUP_DIR output directory        (default: D:\eaiselp\backups)
REM   KEEP_DAYS  retention days          (default: 7)
REM =====================================================================

set "CONTAINER=eaiselp-mysql"
set "DB_NAME=eaiselp"
set "DB_USER=root"
set "DB_PASS=root"
set "BACKUP_DIR=D:\eaiselp\backups"
set "KEEP_DAYS=7"

REM --- build timestamp YYYYMMDD_HHMMSS ---
for /f "tokens=2 delims==" %%a in ('wmic os get localdatetime /value 2^>nul ^| findstr "="') do set "_ldt=%%a"
if not defined _ldt (
    echo [ERROR] cannot get local datetime via wmic
    exit /b 1
)
set "_TS=!_ldt:~0,8 !_ldt:~8,6!"
REM wmic yields local time, no TZ adjustment needed

set "OUTFILE=%BACKUP_DIR%\eaiselp_%_TS%.sql"

if not exist "%BACKUP_DIR%" (
    echo [INFO] create backup dir: %BACKUP_DIR%
    mkdir "%BACKUP_DIR%"
)

echo [INFO] dumping %DB_NAME% from container %CONTAINER% ...
echo [INFO] output: %OUTFILE%

docker exec %CONTAINER% mysqldump -u%DB_USER% -p%DB_PASS% --single-transaction --routines --triggers --events %DB_NAME% > "%OUTFILE%"
if errorlevel 1 (
    echo [ERROR] mysqldump failed, exit code %errorlevel%
    del /f /q "%OUTFILE%" 2>nul
    exit /b 1
)

for %%I in ("%OUTFILE%") do set "_SIZE=%%~zI"
echo [OK] backup done: %OUTFILE% !_SIZE! bytes

REM --- prune backups older than KEEP_DAYS days (forfiles works on file mtime) ---
echo [INFO] prune backups older than %KEEP_DAYS% days under %BACKUP_DIR% ...
forfiles /p "%BACKUP_DIR%" /m "eaiselp_*.sql" /d -%KEEP_DAYS% /c "cmd /c echo   delete @path & del /f /q @path" 2>nul
if errorlevel 1 (
    REM forfiles returns 1 when no files match the age filter, which is normal here
    echo [INFO] no expired backups to delete
)

echo [DONE]
endlocal
exit /b 0
