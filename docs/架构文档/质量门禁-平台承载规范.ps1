<#
.SYNOPSIS
  Platform-bearing quality gate (G11-G15) - enforces EA blueprint P6/P8/P10/P11/P13 + ES-003.
.DESCRIPTION
  Run from project root (the dir containing pom.xml). Checks rules G11-G15.
  Phased gate: G11/G12/G15 are WARN before SP-4/SP-6 complete, BLOCKER after.
  G13 is BLOCKER (no ADR). G14 is WARN (M1 legacy migration).
  Rule definitions and pass/fail criteria: see Quality-Gate-Platform-Bearing.md (same dir).
.NOTES
  Requires only Windows PowerShell 5.1+ (built into Windows 10+).
  All user-facing strings are pure ASCII on purpose: PowerShell 5.1 reads .ps1 files
  as ANSI by default when there is no BOM, which garbles UTF-8 Chinese and breaks
  parsing. ASCII keeps the script encoding-agnostic and robust across locales.
  Chinese path (docs/.../) works because the shell that launches pwsh already
  resolves the path; inside the script we build paths from $Root param.
  Usage:
    powershell -ExecutionPolicy Bypass -File .\docs\architecture\gate-platform-bearing.ps1
    powershell -ExecutionPolicy Bypass -File .\docs\architecture\gate-platform-bearing.ps1 -Phase m2-sp4-done
  Phase values:
    m1            (default) G11/G12/G15 = WARN, G13 = BLOCKER, G14 = WARN
    m2-sp4-done   G11/G15 upgrade to BLOCKER (SP-4 guessType config + artifact frontmatter done)
    m2-sp6-done   G12 upgrades to BLOCKER (SP-6 MODEL_MAPPING migrated to t_model_routing)
    m2-all-done   G11/G12/G15 all BLOCKER
#>

param(
    [string]$Root = (Get-Location).Path,
    [ValidateSet('m1','m2-sp4-done','m2-sp6-done','m2-all-done')]
    [string]$Phase = 'm1'
)

$ErrorActionPreference = 'Stop'
$blockers = @()
$warnings = @()

function Write-Header {
    param([string]$Id, [string]$Desc)
    Write-Host ""
    Write-Host "===== $Id : $Desc =====" -ForegroundColor Cyan
}

function Write-Result {
    param(
        [string]$Id,
        [ValidateSet('PASS','WARN','BLOCKER')]
        [string]$Status,
        [string]$Detail,
        [string[]]$Evidence
    )
    switch ($Status) {
        'PASS'    { Write-Host "[PASS] $Id" -ForegroundColor Green }
        'WARN'    { Write-Host "[WARN] $Id" -ForegroundColor Yellow; $script:warnings += $Id }
        'BLOCKER' { Write-Host "[BLOCKER] $Id" -ForegroundColor Red; $script:blockers += $Id }
    }
    if ($Detail) { Write-Host "       $Detail" -ForegroundColor DarkGray }
    if ($Evidence) {
        Write-Host "       Evidence:" -ForegroundColor $(if ($Status -eq 'PASS') {'DarkGray'} else {'Red'})
        $Evidence | ForEach-Object { Write-Host "         $_" -ForegroundColor $(if ($Status -eq 'PASS') {'DarkGray'} else {'Red'}) }
    }
}

# Strip a Java line to test whether it is a comment line (excluded from violations).
# Recognized as comment:
#   - single-line: first non-ws chars are //
#   - block continuation: first non-ws char is * (covers Javadoc/block middle lines like " * text")
#   - block start: first non-ws chars are /*
function Test-IsCommentLine {
    param([string]$Line)
    $trimmed = $Line.TrimStart()
    if ($trimmed.StartsWith('//')) { return $true }
    if ($trimmed.StartsWith('/*')) { return $true }
    if ($trimmed.StartsWith('*'))  { return $true }
    return $false
}

# All eaiselp-* modules to scan for java source
$allModules = @(
    'eaiselp-common','eaiselp-capability','eaiselp-adapter','eaiselp-data',
    'eaiselp-runtime','eaiselp-gateway','eaiselp-auth','eaiselp-admin','eaiselp-observability'
)

# Helper: collect *.java under src/main/java of given modules
function Get-JavaFiles {
    param([string[]]$Modules)
    $files = @()
    foreach ($m in $Modules) {
        $src = Join-Path $Root "$m\src\main\java"
        if (Test-Path $src) {
            $files += @(Get-ChildItem -Path $src -Recurse -Filter *.java -File)
        }
    }
    return $files
}

# Resolve phase -> per-rule severity
$g11Severity = if ($Phase -in @('m2-sp4-done','m2-all-done')) { 'BLOCKER' } else { 'WARN' }
$g12Severity = if ($Phase -in @('m2-sp6-done','m2-all-done')) { 'BLOCKER' } else { 'WARN' }
$g15Severity = if ($Phase -in @('m2-sp4-done','m2-all-done')) { 'BLOCKER' } else { 'WARN' }

Write-Host ""
Write-Host "############ Platform-Bearing Quality Gate (G11-G15) ############" -ForegroundColor White
Write-Host "Root  : $Root" -ForegroundColor DarkGray
Write-Host "Phase : $Phase  (G11=$g11Severity G12=$g12Severity G13=BLOCKER G14=WARN G15=$g15Severity)" -ForegroundColor DarkGray

# ----------------------------------------------------------------------
# G11: Platform zero role hardcode (P6)
# ----------------------------------------------------------------------
Write-Header -Id 'G11' -Desc 'Platform zero role-name hardcode in eaiselp-*/src/main/java (P6, ES-003 S1)'
$rolePattern = 'team-po|team-ux|team-se|team-dev|team-ba|team-qa|team-reviewer|team-security|team-performance|team-ops|team-pm'
$g11Files = Get-JavaFiles -Modules $allModules
$g11Hits = @()
foreach ($f in $g11Files) {
    $matches = Select-String -Path $f.FullName -Pattern $rolePattern
    foreach ($m in $matches) {
        if (Test-IsCommentLine -Line $m.Line) { continue }  # exclude single-line comments
        $rel = $f.FullName.Substring($Root.Length).TrimStart('\','/')
        $g11Hits += "$rel`:$($m.LineNumber) -> $($m.Line.Trim())"
    }
}
if ($g11Hits.Count -eq 0) {
    Write-Result -Id 'G11' -Status 'PASS' -Detail "No role-name hardcode in eaiselp-*/src/main/java (non-comment lines). Scanned $($g11Files.Count) java files."
} else {
    $detail = "Found $($g11Hits.Count) role-name hardcode(s). SP-4 must refactor guessType to yml eaiselp.artifact.type-mapping."
    Write-Result -Id 'G11' -Status $g11Severity -Detail $detail -Evidence $g11Hits
}

# ----------------------------------------------------------------------
# G12: Model routing config-ized (P8)
# ----------------------------------------------------------------------
Write-Header -Id 'G12' -Desc 'No inline model-name literals / MODEL_MAPPING in eaiselp-*/src/main/java (P8, ES-003 S2)'
$modelPattern = 'glm-4-plus|glm-4-flash|glm-4-long|glm-4-air|MODEL_MAPPING'
$g12Modules = @('eaiselp-adapter','eaiselp-runtime','eaiselp-data')
$g12Files = Get-JavaFiles -Modules $g12Modules
$g12Hits = @()
foreach ($f in $g12Files) {
    $matches = Select-String -Path $f.FullName -Pattern $modelPattern
    foreach ($m in $matches) {
        if (Test-IsCommentLine -Line $m.Line) { continue }
        $rel = $f.FullName.Substring($Root.Length).TrimStart('\','/')
        $g12Hits += "$rel`:$($m.LineNumber) -> $($m.Line.Trim())"
    }
}
if ($g12Hits.Count -eq 0) {
    Write-Result -Id 'G12' -Status 'PASS' -Detail "No inline model-name literals in eaiselp-adapter/runtime/data java. Scanned $($g12Files.Count) java files."
} else {
    $detail = "Found $($g12Hits.Count) inline model literal(s). SP-6 must migrate MODEL_MAPPING to t_model_routing table."
    Write-Result -Id 'G12' -Status $g12Severity -Detail $detail -Evidence $g12Hits
}

# ----------------------------------------------------------------------
# G13: Multi-tenant isolation (P11) - no @InterceptorIgnore without ADR
# ----------------------------------------------------------------------
Write-Header -Id 'G13' -Desc 'No @InterceptorIgnore(tenantLine) without EA-approved ADR (P11, ES-003 S3)'
$g13Files = Get-JavaFiles -Modules $allModules
$g13Violations = @()   # hits with no ADR nearby
$g13Approved   = @()   # hits with ADR-xxx nearby (allowed)
foreach ($f in $g13Files) {
    $lines = @(Get-Content -Path $f.FullName)
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '@InterceptorIgnore') {
            # look at surrounding 3 lines for ADR-xxx approval
            $start = [Math]::Max(0, $i - 3)
            $end   = [Math]::Min($lines.Count - 1, $i + 3)
            $window = ($lines[$start..$end]) -join ' '
            $rel = $f.FullName.Substring($Root.Length).TrimStart('\','/')
            $loc = "$rel`:$($i+1) -> $($lines[$i].Trim())"
            if ($window -match 'ADR-\d+') {
                $g13Approved += "$loc  (ADR approval found in window)"
            } else {
                $g13Violations += $loc
            }
        }
    }
}
if ($g13Violations.Count -eq 0) {
    if ($g13Approved.Count -gt 0) {
        Write-Result -Id 'G13' -Status 'PASS' -Detail "No @InterceptorIgnore without ADR. $($g13Approved.Count) hit(s) have ADR approval (allowed)." -Evidence $g13Approved
    } else {
        Write-Result -Id 'G13' -Status 'PASS' -Detail "No @InterceptorIgnore usage in eaiselp-*/src/main/java. Multi-tenant interceptor enforced everywhere."
    }
} else {
    $detail = "Found $($g13Violations.Count) @InterceptorIgnore without ADR approval. Each bypass needs EA-approved ADR."
    $ev = $g13Violations
    if ($g13Approved.Count -gt 0) { $ev += @("--- approved (allowed) ---") + $g13Approved }
    Write-Result -Id 'G13' -Status 'BLOCKER' -Detail $detail -Evidence $ev
}

# ----------------------------------------------------------------------
# G14: API versioning (P13)
# ----------------------------------------------------------------------
Write-Header -Id 'G14' -Desc 'New Controller @RequestMapping should start with /api/v1/ (P13, ES-003 S4)'
$mappingPattern = '@RequestMapping|@GetMapping|@PostMapping|@PutMapping|@DeleteMapping|@PatchMapping'
$g14Files = Get-JavaFiles -Modules $allModules
$g14Missing = @()
foreach ($f in $g14Files) {
    # only inspect files that look like controllers
    if ($f.Name -notmatch 'Controller\.java$') { continue }
    $matches = Select-String -Path $f.FullName -Pattern $mappingPattern
    foreach ($m in $matches) {
        $line = $m.Line
        # extract the path string(s) from the annotation
        $pathMatches = [regex]::Matches($line, '"(/[^"]*)"')
        foreach ($pm in $pathMatches) {
            $path = $pm.Groups[1].Value
            if ($path -notmatch '^/api/v1/' -and $path -notmatch '^/actuator' -and $path -notmatch '^/health') {
                $rel = $f.FullName.Substring($Root.Length).TrimStart('\','/')
                $g14Missing += "$rel`:$($m.LineNumber) path='$path' (expected /api/v1/...)"
            }
        }
    }
}
if ($g14Missing.Count -eq 0) {
    Write-Result -Id 'G14' -Status 'PASS' -Detail "All Controller mappings start with /api/v1/ (or actuator/health)."
} else {
    $detail = "Found $($g14Missing.Count) mapping(s) without /api/v1/ prefix. M1 legacy (RuntimeController/CapabilityController/AdapterController) migrates in SP-2/SP-3."
    Write-Result -Id 'G14' -Status 'WARN' -Detail $detail -Evidence $g14Missing
}

# ----------------------------------------------------------------------
# G15: Process artifact into platform (P10)
# ----------------------------------------------------------------------
Write-Header -Id 'G15' -Desc 't_artifact frontmatter/doc_key/contract_key should not be all NULL (P10, ES-003 S5)'
$schemaPath = Join-Path $Root 'eaiselp-data\src\main\resources\db\schema.sql'
$g15Columns = @('frontmatter','doc_key','contract_key')
$g15Found = @()
$g15HasSeed = $false
if (Test-Path $schemaPath) {
    $schemaText = Get-Content -Path $schemaPath -Raw
    $bt = [char]96   # backtick (ASCII 96); avoids escape issues in PS strings
    foreach ($col in $g15Columns) {
        $colPat = $bt + $col + $bt
        if ($schemaText -match $colPat) { $g15Found += $col }
    }
    # crude check for t_artifact seed inserts (backtick is optional MySQL identifier quote)
    $bt = [char]96
    $seedPat = 'INSERT\s+INTO\s+' + $bt + '?t_artifact' + $bt + '?'
    if ($schemaText -match $seedPat) { $g15HasSeed = $true }
}
if ($g15Found.Count -lt 3) {
    Write-Result -Id 'G15' -Status 'WARN' -Detail "schema.sql missing some of frontmatter/doc_key/contract_key columns (found: $($g15Found -join ',')). Check schema definition."
} elseif (-not $g15HasSeed) {
    # columns exist but no seed with values -> SP-4 must populate frontmatter on derivation
    Write-Result -Id 'G15' -Status $g15Severity -Detail "t_artifact has columns $($g15Found -join ',') but no seed INSERT with non-NULL values. SP-4 must populate frontmatter in DerivationEngine. (Live DB NULL-count check needs DB connection; this is the schema-level proxy.)"
} else {
    Write-Result -Id 'G15' -Status 'PASS' -Detail "t_artifact schema has frontmatter/doc_key/contract_key and seed data present. (Live DB NULL-count check needs DB connection.)"
}

# ----------------------------------------------------------------------
# Summary
# ----------------------------------------------------------------------
Write-Host ""
Write-Host "================= Gate Summary (G11-G15) =================" -ForegroundColor Yellow
$total = 5
Write-Host ("Phase : {0}" -f $Phase) -ForegroundColor DarkGray
Write-Host ("WARN  : {0}   ({1})" -f $warnings.Count, ($warnings -join ', ')) -ForegroundColor $(if ($warnings.Count -eq 0) {'Green'} else {'Yellow'})
Write-Host ("BLOCK : {0}   ({1})" -f $blockers.Count, ($blockers -join ', ')) -ForegroundColor $(if ($blockers.Count -eq 0) {'Green'} else {'Red'})

if ($blockers.Count -gt 0) {
    Write-Host ""
    Write-Host "Verdict: PR MUST be rejected. Fix BLOCKER(s), then re-run ALL G11-G15." -ForegroundColor Red
    exit 1
} elseif ($warnings.Count -gt 0) {
    Write-Host ""
    Write-Host "Verdict: No blockers. WARN item(s) are known tech-debt (clears as SP-4/SP-6 complete). Proceed to human review." -ForegroundColor Yellow
    exit 0
} else {
    Write-Host ""
    Write-Host "Verdict: All gates passed. Proceed to human review." -ForegroundColor Green
    exit 0
}
