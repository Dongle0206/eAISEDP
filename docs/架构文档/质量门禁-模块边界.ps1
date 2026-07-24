<#
.SYNOPSIS
  Module boundary quality gate (compile-time) - enforces ADR-001 P1/P2/P4 + ES-001.
.DESCRIPTION
  Run from project root (the dir containing pom.xml). Checks rules G1-G6.
  Any blocker failure -> exit code 1.
  QA runs manually in M1; will be ported to CI pipeline in M2.
  Rule definitions and pass/fail criteria: see Quality-Gate-Module-Boundary.md (same dir).
.NOTES
  Requires only Windows PowerShell 5.1+ (built into Windows 10+). No git bash needed.
  All user-facing strings are pure ASCII on purpose: PowerShell 5.1 reads .ps1 files
  as ANSI by default when there is no BOM, which garbles UTF-8 Chinese and breaks
  parsing. ASCII keeps the script encoding-agnostic and robust across locales.
  Usage:
    powershell -ExecutionPolicy Bypass -File .\docs\architecture\gate-module-boundary.ps1
    (Chinese path works because the shell that launches pwsh already resolves the path.)
#>

param(
    [string]$Root = (Get-Location).Path
)

$ErrorActionPreference = 'Stop'
$failures = @()

function Test-Rule {
    param(
        [string]$Id,
        [string]$Desc,
        [scriptblock]$Check
    )
    Write-Host ""
    Write-Host "===== $Id : $Desc =====" -ForegroundColor Cyan
    try {
        $result = & $Check
        if ($result.Passed) {
            Write-Host "[PASS] $Id" -ForegroundColor Green
            if ($result.Detail) { Write-Host "      $($result.Detail)" -ForegroundColor DarkGray }
            return $true
        } else {
            Write-Host "[FAIL] $Id" -ForegroundColor Red
            Write-Host "       $($result.Reason)" -ForegroundColor Red
            if ($result.Evidence) {
                Write-Host "       Evidence:" -ForegroundColor Red
                $result.Evidence | ForEach-Object { Write-Host "         $_" -ForegroundColor Red }
            }
            $script:failures += $Id
            return $false
        }
    } catch {
        Write-Host "[ERROR] $Id check threw: $($_.Exception.Message)" -ForegroundColor Magenta
        $script:failures += "$Id(script-error)"
        return $false
    }
}

# Flatten multi-line XML into one line so regex can match across lines/indentation.
function Get-PomFlatText {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return $null }
    return ((Get-Content $Path -Raw) -replace '\s+', ' ')
}

$libraryModules = @('eaiselp-common', 'eaiselp-capability', 'eaiselp-adapter', 'eaiselp-data')
$serviceModules = @('eaiselp-runtime', 'eaiselp-gateway', 'eaiselp-auth', 'eaiselp-observability', 'eaiselp-admin')

# G1: parent POM pluginManagement/spring-boot-maven-plugin must NOT contain <executions>
Test-Rule -Id 'G1' -Desc 'Parent POM pluginManagement spring-boot-maven-plugin must NOT have executions' -Check {
    $parentPom = Join-Path $Root 'pom.xml'
    if (-not (Test-Path $parentPom)) {
        return @{ Passed = $false; Reason = "Parent POM not found: $parentPom" }
    }
    $flat = Get-PomFlatText -Path $parentPom
    $pmMatch = [regex]::Match($flat, '<pluginManagement>(.*?)</pluginManagement>')
    if (-not $pmMatch.Success) {
        return @{ Passed = $true; Detail = 'No pluginManagement section (no global repackage recipe risk)' }
    }
    $pm = $pmMatch.Groups[1].Value
    $pluginMatch = [regex]::Match($pm, '<plugin>\s*<groupId>\s*org\.springframework\.boot\s*</groupId>\s*<artifactId>\s*spring-boot-maven-plugin\s*</artifactId>(.*?)</plugin>')
    if (-not $pluginMatch.Success) {
        return @{ Passed = $true; Detail = 'spring-boot-maven-plugin not declared inside pluginManagement' }
    }
    $pluginBody = $pluginMatch.Groups[1].Value
    if ($pluginBody -match '<executions>') {
        $line = (Select-String -Path $parentPom -Pattern '<executions>' -SimpleMatch | Select-Object -First 1).LineNumber
        return @{
            Passed  = $false
            Reason  = 'pluginManagement/spring-boot-maven-plugin contains <executions> (ADR-001 P4 root cause)'
            Evidence = @("pom.xml:$line matched <executions>")
        }
    }
    return @{ Passed = $true; Detail = 'pluginManagement/spring-boot-maven-plugin has configuration only, no executions' }
}

# G2: library module POMs must NOT contain spring-boot-maven-plugin
Test-Rule -Id 'G2' -Desc 'Library module POMs must NOT contain spring-boot-maven-plugin' -Check {
    $violations = @()
    foreach ($m in $libraryModules) {
        $pom = Join-Path $Root "$m\pom.xml"
        if (-not (Test-Path $pom)) {
            $violations += "$m\pom.xml not found"
            continue
        }
        $line = (Select-String -Path $pom -Pattern 'spring-boot-maven-plugin' -SimpleMatch |
                 Where-Object { $_.Line -notmatch '^\s*<!--' } | Select-Object -First 1)
        if ($line) {
            $violations += "$m\pom.xml:$($line.LineNumber) matched spring-boot-maven-plugin"
        }
    }
    if ($violations.Count -gt 0) {
        return @{ Passed = $false; Reason = 'Library module POM contains spring-boot-maven-plugin (P1/P2)'; Evidence = $violations }
    }
    return @{ Passed = $true; Detail = ($libraryModules -join ', ') + ' all clean' }
}

# G3: library modules must NOT have @SpringBootApplication
Test-Rule -Id 'G3' -Desc 'Library modules must NOT have @SpringBootApplication classes' -Check {
    $violations = @()
    foreach ($m in $libraryModules) {
        $src = Join-Path $Root "$m\src\main\java"
        if (-not (Test-Path $src)) { continue }
        $hits = Get-ChildItem -Path $src -Recurse -Filter *.java |
                Select-String -Pattern '@SpringBootApplication' -SimpleMatch
        foreach ($h in $hits) {
            $rel = $h.Path.Substring($Root.Length).TrimStart('\','/')
            $violations += "${rel}:$($h.LineNumber) @SpringBootApplication"
        }
    }
    if ($violations.Count -gt 0) {
        return @{ Passed = $false; Reason = 'Library module has @SpringBootApplication class (delete Application class; endpoints are taken over by host)'; Evidence = $violations }
    }
    return @{ Passed = $true; Detail = 'All 4 library modules have no @SpringBootApplication' }
}

# G4: library modules must NOT have @EnableDiscoveryClient / @EnableEurekaClient
Test-Rule -Id 'G4' -Desc 'Library modules must NOT have @EnableDiscoveryClient / @EnableEurekaClient' -Check {
    $violations = @()
    foreach ($m in $libraryModules) {
        $src = Join-Path $Root "$m\src\main\java"
        if (-not (Test-Path $src)) { continue }
        $hits = Get-ChildItem -Path $src -Recurse -Filter *.java |
                Select-String -Pattern '@EnableDiscoveryClient|@EnableEurekaClient'
        foreach ($h in $hits) {
            $rel = $h.Path.Substring($Root.Length).TrimStart('\','/')
            $violations += "${rel}:$($h.LineNumber) $($h.Line.Trim())"
        }
    }
    if ($violations.Count -gt 0) {
        return @{ Passed = $false; Reason = 'Library module must not register with service discovery (host handles registration)'; Evidence = $violations }
    }
    return @{ Passed = $true; Detail = 'All 4 library modules have no service-discovery annotation' }
}

# G5: library module POMs must NOT depend on spring-cloud-starter-alibaba-nacos-discovery
Test-Rule -Id 'G5' -Desc 'Library module POMs must NOT depend on nacos-discovery' -Check {
    $violations = @()
    foreach ($m in $libraryModules) {
        $pom = Join-Path $Root "$m\pom.xml"
        if (-not (Test-Path $pom)) { continue }
        $line = (Select-String -Path $pom -Pattern 'spring-cloud-starter-alibaba-nacos-discovery' -SimpleMatch |
                 Where-Object { $_.Line -notmatch '^\s*<!--' } | Select-Object -First 1)
        if ($line) {
            $violations += "$m\pom.xml:$($line.LineNumber) matched nacos-discovery dependency"
        }
    }
    if ($violations.Count -gt 0) {
        return @{ Passed = $false; Reason = 'Library module should not depend on nacos-discovery (same root as G4)'; Evidence = $violations }
    }
    return @{ Passed = $true; Detail = 'All 4 library module POMs have no nacos-discovery dependency' }
}

# G6: service modules MUST explicitly declare spring-boot-maven-plugin + repackage executions
Test-Rule -Id 'G6' -Desc 'Service module POMs MUST explicitly declare spring-boot-maven-plugin with repackage executions' -Check {
    $violations = @()
    foreach ($m in $serviceModules) {
        $pom = Join-Path $Root "$m\pom.xml"
        if (-not (Test-Path $pom)) {
            $violations += "$m\pom.xml not found"
            continue
        }
        $flat = Get-PomFlatText -Path $pom
        if ($flat -notmatch '<artifactId>\s*spring-boot-maven-plugin\s*</artifactId>') {
            $violations += "$m\pom.xml missing spring-boot-maven-plugin declaration"
            continue
        }
        $pluginMatch = [regex]::Match($flat, '<plugin>\s*<groupId>\s*org\.springframework\.boot\s*</groupId>\s*<artifactId>\s*spring-boot-maven-plugin\s*</artifactId>(.*?)</plugin>')
        if (-not $pluginMatch.Success -or $pluginMatch.Groups[1].Value -notmatch '<executions>') {
            $violations += "$m\pom.xml spring-boot-maven-plugin missing executions (after P4 parent POM no longer triggers it)"
            continue
        }
        if ($pluginMatch.Groups[1].Value -notmatch '<goal>\s*repackage\s*</goal>') {
            $violations += "$m\pom.xml executions missing repackage goal"
        }
    }
    if ($violations.Count -gt 0) {
        return @{ Passed = $false; Reason = 'Service module POM does not explicitly configure repackage (required after P4)'; Evidence = $violations }
    }
    return @{ Passed = $true; Detail = 'All 5 service modules explicitly have repackage executions' }
}

# Summary
Write-Host ""
Write-Host "================= Gate Summary =================" -ForegroundColor Yellow
$total = 6
$passed = $total - $failures.Count
Write-Host ("PASS: {0}/{1}    FAIL: {2}" -f $passed, $total, $failures.Count) -ForegroundColor $(if ($failures.Count -eq 0) { 'Green' } else { 'Red' })
if ($failures.Count -gt 0) {
    Write-Host "Failed rules: $($failures -join ', ')" -ForegroundColor Red
    Write-Host "Verdict: PR must be rejected; L1-Dev fixes then re-runs ALL G1-G6" -ForegroundColor Red
    exit 1
} else {
    Write-Host "Verdict: All gates passed, proceed to human review" -ForegroundColor Green
    exit 0
}
