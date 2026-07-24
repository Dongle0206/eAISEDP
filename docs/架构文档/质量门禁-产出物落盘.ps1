<#
.SYNOPSIS
  Artifact persistence & execution quality gate (G7-G10) - enforces ES-002.
.DESCRIPTION
  Run from project root (the dir containing pom.xml). Checks rules G7-G10.
  - G7 (blocker): Reviewer / QA / SE key-role artifacts MUST exist as physical files.
  - G8 (blocker): No literal '$null' file polluting the workspace.
  - G9 (warn): Recent git commit subjects must not show mojibake.
  - G10 (warn): Dev reports with change-direction wording must reference HEAD.
  Blocker failure (G7/G8) -> exit code 1. Warning (G9/G10) does NOT fail the gate.
  Rule definitions and pass/fail criteria: see Quality-Gate-Artifact-Persistence.md (same dir).
.NOTES
  Requires only Windows PowerShell 5.1+ (built into Windows 10+). No git bash needed.

  ENCODING POLICY (per ES-002 section 3.5): This script is PURE ASCII. No Chinese
  characters appear as literal strings anywhere in the file. This is critical because
  PowerShell 5.1 reads BOM-less .ps1 files as ANSI (system codepage), which garbles
  UTF-8 Chinese and breaks parsing.

  Path discovery: The artifact directories on this project use Chinese names
  (e.g. docs\<test-report-dir>/, docs\<design-plan-dir>/, docs\<process-track-dir>/).
  To stay pure-ASCII, we do NOT hardcode those Chinese names. Instead we recurse
  docs\ and match by FILENAME patterns. Where matching requires Chinese substrings
  in the filename (e.g. the suffix "<tech-spec>.md"), we build the substring from
  Unicode code points at runtime so the .ps1 file itself stays ASCII.

  Usage:
    powershell -ExecutionPolicy Bypass -File .\docs\<arch-dir>\gate-artifact-persistence.ps1
#>

param(
    [string]$Root = (Get-Location).Path
)

$ErrorActionPreference = 'Stop'
$failures = @()    # blocker failures (G7/G8)
$warnings = @()    # warning failures (G9/G10)

# ---- Chinese substrings built from Unicode code points (keeps .ps1 pure ASCII) ----
# Test report dir name (测试报告 = test report)
$DIR_TEST_REPORT = ([char]0x6D4B + [char]0x8BD5 + [char]0x62A5 + [char]0x544A)
# Design plan dir name (设计规划文档 = design plan doc)
$DIR_DESIGN_PLAN = ([char]0x8BBE + [char]0x8BA1 + [char]0x89C4 + [char]0x5212 + [char]0x6587 + [char]0x6863)
# Process tracking dir name (过程跟踪文档 = process tracking doc)
$DIR_PROCESS_TRACK = ([char]0x8FC7 + [char]0x7A0B + [char]0x8DDF + [char]0x8E2A + [char]0x6587 + [char]0x6863)
# Tech spec suffix (技术方案 = tech solution)
$SUFFIX_TECH_SPEC = ([char]0x6280 + [char]0x672F + [char]0x65B9 + [char]0x6848)
# Change-direction keywords (for G10)
$KW_NEW    = ([char]0x65B0 + [char]0x589E)              # 新增
$KW_DELETE = ([char]0x5220 + [char]0x9664)              # 删除
$KW_MODIFY = ([char]0x4FEE + [char]0x6539)              # 修改
$KW_ADD    = ([char]0x6DFB + [char]0x52A0)              # 添加
$HEAD_REF_A = ('HEAD' )                                  # we look for literal 'HEAD' word

function Test-Rule {
    param(
        [string]$Id,
        [string]$Desc,
        [string]$Level,   # 'blocker' or 'warn'
        [scriptblock]$Check
    )
    Write-Host ""
    Write-Host "===== $Id : $Desc =====" -ForegroundColor Cyan
    try {
        $result = & $Check
        if ($result.Status -eq 'PASS') {
            Write-Host "[PASS] $Id" -ForegroundColor Green
            if ($result.Detail) { Write-Host "      $($result.Detail)" -ForegroundColor DarkGray }
        } elseif ($result.Status -eq 'NA') {
            Write-Host "[N/A]  $Id" -ForegroundColor DarkGray
            if ($result.Detail) { Write-Host "      $($result.Detail)" -ForegroundColor DarkGray }
        } elseif ($result.Status -eq 'FAIL') {
            if ($Level -eq 'blocker') {
                Write-Host "[FAIL] $Id (blocker)" -ForegroundColor Red
                $script:failures += $Id
            } else {
                Write-Host "[WARN] $Id (warning)" -ForegroundColor Yellow
                $script:warnings += $Id
            }
            Write-Host "       $($result.Reason)" -ForegroundColor $(if ($Level -eq 'blocker') { 'Red' } else { 'Yellow' })
            if ($result.Evidence) {
                Write-Host "       Evidence:" -ForegroundColor $(if ($Level -eq 'blocker') { 'Red' } else { 'Yellow' })
                $result.Evidence | ForEach-Object { Write-Host "         $_" -ForegroundColor $(if ($Level -eq 'blocker') { 'Red' } else { 'Yellow' }) }
            }
        }
    } catch {
        Write-Host "[ERROR] $Id check threw: $($_.Exception.Message)" -ForegroundColor Magenta
        $script:failures += "$Id(script-error)"
    }
}

# Helper: list .md files under docs\{chineseDir}\ matching a filename predicate.
# Uses Get-ChildItem with -Filter on ASCII prefix where possible; falls back to
# post-filter by full name (memory-side) so Chinese suffix matches work.
function Get-MdFilesInDir {
    param(
        [string]$DirChineseName,   # runtime-built Chinese string
        [scriptblock]$NamePredicate
    )
    $dir = Join-Path (Join-Path $Root 'docs') $DirChineseName
    if (-not (Test-Path $dir)) { return @() }
    $all = @(Get-ChildItem -Path $dir -File -ErrorAction SilentlyContinue)
    return @($all | Where-Object { & $NamePredicate $_.Name })
}

# ============================================================
# G7: Key-role artifacts MUST exist as physical files (blocker)
#     Categories (each must have >= 1 file > 100 bytes):
#       - Reviewer reports: docs\<test-report>\review-*.md
#       - QA reports:       docs\<test-report>\qa-*.md
#       - SE tech specs:    docs\<design-plan>\*<tech-spec>.md
# ============================================================
Test-Rule -Id 'G7' -Desc 'Key-role artifacts (Reviewer/QA/SE) must exist as physical files' -Level 'blocker' -Check {
    $minBytes = 100
    $categories = @(
        @{ Name = 'Reviewer report'; Dir = $DIR_TEST_REPORT;  Predicate = { param($n) $n -like 'review-*.md' } },
        @{ Name = 'QA report';       Dir = $DIR_TEST_REPORT;  Predicate = { param($n) $n -like 'qa-*.md' } },
        @{ Name = 'SE tech spec';    Dir = $DIR_DESIGN_PLAN;  Predicate = { param($n) $n -like ('*' + $SUFFIX_TECH_SPEC + '.md') } }
    )
    $missing = @()
    $found = @()
    foreach ($cat in $categories) {
        $files = Get-MdFilesInDir -DirChineseName $cat.Dir -NamePredicate $cat.Predicate
        $valid = @($files | Where-Object { $_.Length -gt $minBytes })
        if ($valid.Count -ge 1) {
            $sample = $valid[0]
            $rel = $sample.FullName.Substring($Root.Length).TrimStart('\','/')
            $found += "$($cat.Name): $rel ($($sample.Length) bytes)"
        } else {
            if ($files.Count -ge 1) {
                $small = $files[0]
                $rel = $small.FullName.Substring($Root.Length).TrimStart('\','/')
                $missing += "$($cat.Name): has $($files.Count) file(s) but none > $minBytes bytes (smallest $($small.Length) bytes at $rel)"
            } else {
                $missing += "$($cat.Name): no matching file under docs\$($cat.Dir)"
            }
        }
    }
    if ($missing.Count -gt 0) {
        return @{
            Status   = 'FAIL'
            Reason   = 'One or more key-role artifact categories are missing physical files (ES-002 section 1, defect IMP-004)'
            Evidence = $missing
        }
    }
    return @{
        Status = 'PASS'
        Detail = ('All 3 categories present: ' + ($found -join '; '))
    }
}

# ============================================================
# G8: No literal '$null' file polluting the workspace (blocker)
# ============================================================
Test-Rule -Id 'G8' -Desc 'Workspace must NOT contain literal $null file' -Level 'blocker' -Check {
    # Scan workspace recursively, skip .git internals
    $hits = @(Get-ChildItem -Path $Root -Recurse -Force -File -ErrorAction SilentlyContinue |
              Where-Object {
                  $_.Name -eq '$null' -and
                  (-not ($_.FullName -match '\\\.git\\'))
              })
    if ($hits.Count -gt 0) {
        $ev = $hits | ForEach-Object { $_.FullName }
        return @{
            Status   = 'FAIL'
            Reason   = 'Found literal $null file(s) in workspace (ES-002 section 3.2, defect IMP-006)'
            Evidence = $ev
        }
    }
    return @{
        Status = 'PASS'
        Detail = 'No literal $null file found in workspace'
    }
}

# ============================================================
# G9: Recent git commit subjects must not show mojibake (warn)
# ============================================================
Test-Rule -Id 'G9' -Desc 'Recent git commit subjects must not show mojibake' -Level 'warn' -Check {
    $gitDir = Join-Path $Root '.git'
    if (-not (Test-Path $gitDir)) {
        return @{ Status = 'NA'; Detail = 'Not a git repo, skipped' }
    }
    $subjects = @(git -C $Root log -5 --format='%s' 2>&1 | Where-Object { $_ -is [string] })
    if ($subjects.Count -eq 0) {
        return @{ Status = 'NA'; Detail = 'No commits yet, skipped' }
    }
    # Mojibake heuristics: triple question marks OR backslash-octal escapes
    $suspect = @()
    foreach ($s in $subjects) {
        if ($s -match '\?\?\?' -or $s -match '\\[0-3][0-7]{2}') {
            $suspect += $s
        }
    }
    if ($suspect.Count -gt 0) {
        return @{
            Status   = 'FAIL'
            Reason   = 'Recent commit subjects show possible mojibake (ES-002 section 3.1, defect IMP-006)'
            Evidence = $suspect
        }
    }
    return @{
        Status = 'PASS'
        Detail = ("Last {0} commit subject(s) look clean" -f $subjects.Count)
    }
}

# ============================================================
# G10: Dev reports with change-direction wording must reference HEAD (warn)
# ============================================================
Test-Rule -Id 'G10' -Desc 'Dev reports with change wording must reference HEAD' -Level 'warn' -Check {
    $caseRoot = Join-Path (Join-Path $Root 'docs') $DIR_PROCESS_TRACK
    if (-not (Test-Path $caseRoot)) {
        return @{ Status = 'NA'; Detail = 'No process-tracking dir, skipped' }
    }
    $reports = @(Get-ChildItem -Path $caseRoot -Recurse -Filter 'dev-report-*.md' -File -ErrorAction SilentlyContinue)
    if ($reports.Count -eq 0) {
        return @{ Status = 'NA'; Detail = 'No dev-report-*.md found, skipped (M1.1 stage Dev report was in reply, not on disk)' }
    }
    $violations = @()
    foreach ($r in $reports) {
        $content = Get-Content -Path $r.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if (-not $content) { continue }
        $changeWordPattern = ($KW_NEW + '|' + $KW_DELETE + '|' + $KW_MODIFY + '|' + $KW_ADD)
        $hasChangeWord = $content -match $changeWordPattern
        $hasHeadRef    = $content -match 'HEAD'
        if ($hasChangeWord -and -not $hasHeadRef) {
            $rel = $r.FullName.Substring($Root.Length).TrimStart('\','/')
            $violations += "$rel contains change-direction wording but no HEAD reference"
        }
    }
    if ($violations.Count -gt 0) {
        return @{
            Status   = 'FAIL'
            Reason   = 'Dev report(s) describe change direction without referencing HEAD (ES-002 section 4, defect IMP-007)'
            Evidence = $violations
        }
    }
    return @{
        Status = 'PASS'
        Detail = ("Checked {0} dev-report file(s), all reference HEAD when describing changes" -f $reports.Count)
    }
}

# ============================================================
# Summary
# ============================================================
Write-Host ""
Write-Host "================= Gate Summary =================" -ForegroundColor Yellow
$total = 4
$blockerFail = $failures.Count
$warnFail = $warnings.Count
Write-Host ("Blockers failed: {0}    Warnings failed: {1}    Total rules: {2}" -f $blockerFail, $warnFail, $total) -ForegroundColor $(if ($blockerFail -eq 0) { 'Green' } else { 'Red' })
if ($failures.Count -gt 0) {
    Write-Host "Failed blockers: $($failures -join ', ')" -ForegroundColor Red
}
if ($warnings.Count -gt 0) {
    Write-Host "Failed warnings: $($warnings -join ', ')" -ForegroundColor Yellow
}

if ($failures.Count -gt 0) {
    Write-Host "Verdict: BLOCKER failure -> PR must be rejected; fix then re-run ALL G7-G10" -ForegroundColor Red
    exit 1
} elseif ($warnings.Count -gt 0) {
    Write-Host "Verdict: Only warnings -> PR may proceed; fix warnings during case archive" -ForegroundColor Yellow
    exit 0
} else {
    Write-Host "Verdict: All gates passed (incl. warnings)" -ForegroundColor Green
    exit 0
}
