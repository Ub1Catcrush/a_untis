<#
.SYNOPSIS
    One-stop F-Droid setup for A*Untis: takes the repository from "current code" to a
    submittable merge request for f-droid.org (and optionally sets up your own F-Droid repo).

.DESCRIPTION
    Run it from anywhere (double-click setup-fdroid.bat, or: powershell -File setup-fdroid.ps1).
    Every step is idempotent - run it again after each release.

      1. Reads the current version from dependencies.gradle (versionCode = major*1000000 +
         minor*10000 + patch, same formula as app/build.gradle.kts).
      2. Checks the tools (git, Docker, ...) and offers to install missing ones via winget.
      3. Checks the repository against the F-Droid rules (license, fastlane texts and sizes,
         proprietary libraries, the manifest line the recipe patches, F-Droid switch in Gradle).
      4. Brings fdroid/metadata/com.webuntis.dashboard.yml up to date (Builds, CurrentVersion*).
      5. Creates the per-version changelog (fastlane .../changelogs/<versionCode>.txt) if missing.
      6. Commits the F-Droid files, makes sure the release tag exists and is pushed, and checks
         that the TAG (= what F-Droid builds) really contains F-Droid support.
      7. Prepares your fdroiddata fork (branch com.webuntis.dashboard), copies the recipe in, runs
         readmeta / rewritemeta / checkupdates / lint / build in F-Droid's official build-server
         container, commits "New App: com.webuntis.dashboard" and pushes the branch.
      8. Prints (and copies) the merge-request title/description and opens the MR page.
      9. Optional (-SetupOwnRepo): creates the key for your OWN F-Droid repo on GitHub Pages and
         sets the four GitHub secrets that .github/workflows/fdroid.yml needs.

    What stays manual (cannot be automated): forking fdroiddata on GitLab (the script opens the
    page), clicking "Create merge request", and answering the reviewers.

.PARAMETER GitLabUser     GitLab account that owns your public fdroiddata fork.
.PARAMETER WorkDir        Where fdroiddata / fdroidserver clones and the repo key live.
.PARAMETER LocalOnly      Only steps 1-6 on this repository: no network, no Docker, no push.
.PARAMETER SkipBuild      Skip the slow container build (the fork's GitLab CI will build it).
.PARAMETER NoPush         Do everything locally, push nothing (neither GitHub nor GitLab).
.PARAMETER SetupOwnRepo   Also set up the own F-Droid repository (key + GitHub secrets).
.PARAMETER KeepOldBuilds  Keep older "Builds:" entries instead of replacing them by the current one.
.PARAMETER Yes            Answer every confirmation with yes (non-interactive).

.EXAMPLE
    fdroid\setup-fdroid.bat -GitLabUser myname
    fdroid\setup-fdroid.bat -LocalOnly
#>
[CmdletBinding()]
param(
    [string]$GitLabUser = '',
    [string]$WorkDir = '',
    [switch]$LocalOnly,
    [switch]$SkipBuild,
    [switch]$NoPush,
    [switch]$SetupOwnRepo,
    [switch]$KeepOldBuilds,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$AppId   = 'com.webuntis.dashboard'
$ranBuild = $false
$Branch  = $AppId
$script:Warnings = @()
$global:LASTEXITCODE = 0

# ---------------------------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------------------------
function Write-Step([string]$t) { Write-Host ''; Write-Host "=== $t ===" -ForegroundColor Cyan }
function Write-Ok([string]$t)   { Write-Host "  [ok]   $t" -ForegroundColor Green }
function Write-Info([string]$t) { Write-Host "         $t" }
function Write-Warn2([string]$t) { Write-Host "  [warn] $t" -ForegroundColor Yellow; $script:Warnings += $t }
function Write-Bad([string]$t)  { Write-Host "  [FAIL] $t" -ForegroundColor Red }

function Confirm-Action([string]$question) {
    if ($Yes) { Write-Info "$question -> yes (-Yes)"; return $true }
    $a = Read-Host "  $question [y/N]"
    return ($a -match '^(y|j)')
}

function Test-Cmd([string]$name) { return [bool](Get-Command $name -ErrorAction SilentlyContinue) }

# Runs a native program, shows its output, throws on a non-zero exit code.
function Invoke-Native([string]$exe, [string[]]$arguments, [string]$cwd = '') {
    if ($cwd) { Push-Location $cwd }
    $ErrorActionPreference = 'Continue'   # native stderr (git/docker progress) must not abort; the exit code decides
    try {
        & $exe @arguments
        if ($LASTEXITCODE -ne 0) { throw "'$exe $($arguments -join ' ')' failed (exit code $LASTEXITCODE)" }
    } finally {
        if ($cwd) { Pop-Location }
    }
}

# Runs a native program quietly and returns $true when its exit code is 0.
function Test-Native([string]$exe, [string[]]$arguments, [string]$cwd = '') {
    if ($cwd) { Push-Location $cwd }
    $ErrorActionPreference = 'Continue'
    try {
        $null = & $exe @arguments 2>$null
        return ($LASTEXITCODE -eq 0)
    } finally {
        if ($cwd) { Pop-Location }
    }
}

# Runs a native program and returns its output lines, or $null when it fails (stderr discarded).
function Get-NativeOutput([string]$exe, [string[]]$arguments, [string]$cwd = '') {
    if ($cwd) { Push-Location $cwd }
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $exe @arguments 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        return $out
    } finally {
        if ($cwd) { Pop-Location }
    }
}

function Write-TextFile([string]$path, [string]$text) {
    $dir = Split-Path -Parent $path
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Read-TextFile([string]$path) {
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
}

function Install-WithWinget([string]$label, [string]$wingetId) {
    if (-not (Test-Cmd 'winget')) {
        Write-Bad "$label is missing and winget is not available - install it manually."
        return $false
    }
    if (-not (Confirm-Action "Install $label now with winget?")) { return $false }
    Invoke-Native 'winget' @('install', '-e', '--id', $wingetId, '--accept-package-agreements', '--accept-source-agreements')
    Write-Warn2 "$label was installed. Open a NEW terminal (so PATH is refreshed) and run this script again."
    return $true
}

function Assert-Tool([string]$cmd, [string]$label, [string]$wingetId) {
    if (Test-Cmd $cmd) { Write-Ok "$label found"; return }
    Write-Bad "$label ('$cmd') not found"
    if (Install-WithWinget $label $wingetId) { throw "Restart required: re-run the script in a new terminal." }
    throw "$label is required for this step."
}

# ---------------------------------------------------------------------------------------------
# 1. repository + version
# ---------------------------------------------------------------------------------------------
Write-Step '1/9  Repository and version'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = (Resolve-Path (Join-Path $ScriptDir '..')).Path
if (-not (Test-Path (Join-Path $RepoRoot 'dependencies.gradle'))) {
    throw "dependencies.gradle not found in $RepoRoot - keep this script inside <repo>\fdroid\."
}
if (-not $WorkDir) {
    $WorkDir = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'a_untis-fdroid'
}
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

$gradleText = Read-TextFile (Join-Path $RepoRoot 'dependencies.gradle')
if ($gradleText -notmatch 'versionMajor:\s*(\d+)[^\r\n]*versionMinor:\s*(\d+)[^\r\n]*versionPatch:\s*(\d+)') {
    throw 'Could not read versionMajor/versionMinor/versionPatch from dependencies.gradle.'
}
$VersionName = "$($Matches[1]).$($Matches[2]).$($Matches[3])"
$VersionCode = ([int]$Matches[1] * 1000000) + ([int]$Matches[2] * 10000) + [int]$Matches[3]
$Tag         = "v$VersionName"
$RecipePath  = Join-Path $RepoRoot "fdroid/metadata/${AppId}.yml"
if (-not (Test-Path $RecipePath)) { throw "Recipe not found: $RecipePath" }

Write-Ok "Repository : $RepoRoot"
Write-Ok "Version    : $VersionName (versionCode $VersionCode), release tag $Tag"
Write-Info "Work dir   : $WorkDir"
try { Start-Transcript -Path (Join-Path $WorkDir 'setup-fdroid.log') -Append | Out-Null } catch { }

# ---------------------------------------------------------------------------------------------
# 2. tools
# ---------------------------------------------------------------------------------------------
Write-Step '2/9  Tools'
Assert-Tool 'git' 'Git' 'Git.Git'
$needDocker = (-not $LocalOnly) -and (-not $SkipBuild)
if ($needDocker) {
    Assert-Tool 'docker' 'Docker (Desktop)' 'Docker.DockerDesktop'
    if (-not (Test-Native 'docker' @('info'))) {
        throw 'Docker is installed but not running - start Docker Desktop and run the script again (or use -SkipBuild).'
    }
    Write-Ok 'Docker is running'
} else {
    Write-Info 'Docker not needed (-LocalOnly / -SkipBuild).'
}
if ($SetupOwnRepo) {
    Assert-Tool 'keytool' 'Java JDK (keytool)' 'EclipseAdoptium.Temurin.17.JDK'
    Assert-Tool 'gh' 'GitHub CLI (gh)' 'GitHub.cli'
}

# ---------------------------------------------------------------------------------------------
# 3. static checks against the F-Droid rules
# ---------------------------------------------------------------------------------------------
Write-Step '3/9  Repository checks (F-Droid inclusion rules)'
$errors = @()

# license
$licence = Get-ChildItem -Path $RepoRoot -File | Where-Object { $_.Name -match '^(LICEN[CS]E|COPYING)' } | Select-Object -First 1
if (-not $licence) { $errors += 'No license file (LICENCE / LICENSE / COPYING) in the repository root.' }
elseif ((Read-TextFile $licence.FullName) -notmatch 'GNU GENERAL PUBLIC LICENSE') { Write-Warn2 "$($licence.Name) is not the GPL text - check 'License:' in the recipe." }
else { Write-Ok "License file $($licence.Name) (GPL) - recipe says: $((Select-String -Path $RecipePath -Pattern '^License:\s*(.+)$').Matches[0].Groups[1].Value)" }

# fastlane texts
$fastlane = Join-Path $RepoRoot 'fastlane/metadata/android'
$locales = @(Get-ChildItem -Path $fastlane -Directory -ErrorAction SilentlyContinue)
if ($locales.Count -eq 0) { $errors += "No store texts in fastlane\metadata\android\<locale>." }
foreach ($loc in $locales) {
    $n = $loc.Name
    $title = Join-Path $loc.FullName 'title.txt'
    $short = Join-Path $loc.FullName 'short_description.txt'
    $full  = Join-Path $loc.FullName 'full_description.txt'
    foreach ($f in @($title, $short, $full)) { if (-not (Test-Path $f)) { $errors += "[$n] missing $(Split-Path -Leaf $f)" } }
    if ((Test-Path $title) -and (Read-TextFile $title).Trim().Length -gt 30)  { $errors += "[$n] title.txt is longer than 30 characters." }
    if (Test-Path $short) {
        $s = (Read-TextFile $short).Trim()
        if ($s.Length -ge 80)   { $errors += "[$n] short_description.txt must be shorter than 80 characters (is $($s.Length))." }
        if ($s.EndsWith('.'))   { Write-Warn2 "[$n] short_description.txt should not end with a dot." }
    }
    if ((Test-Path $full) -and (Read-TextFile $full).Length -gt 4000) { $errors += "[$n] full_description.txt is longer than 4000 characters." }
    $shots = @(Get-ChildItem -Path (Join-Path $loc.FullName 'images') -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'Screenshots' })
    if ($shots.Count -eq 0) { Write-Warn2 "[$n] no screenshots (images\phoneScreenshots|tenInchScreenshots) - F-Droid shows none." }
}
if ($errors.Count -eq 0 -and $locales.Count -gt 0) { Write-Ok "Store texts OK for: $(($locales | ForEach-Object { $_.Name }) -join ', ')" }

# proprietary libraries
$buildFiles = @(Get-ChildItem -Path $RepoRoot -Recurse -File -Include '*.gradle', '*.gradle.kts', '*.toml' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '[\\/](build|\.gradle)[\\/]' })
$proprietary = 'com\.google\.android\.gms|play-services|com\.google\.firebase|firebase-|crashlytics|com\.google\.mlkit|billingclient|com\.google\.android\.play:|appcenter|com\.facebook\.android'
$hits = @($buildFiles | Select-String -Pattern $proprietary)
if ($hits.Count -gt 0) {
    foreach ($h in $hits) { $errors += "Non-FOSS dependency in $($h.Path):$($h.LineNumber): $($h.Line.Trim())" }
} else { Write-Ok "No proprietary (Google Play / Firebase / ...) dependencies in $($buildFiles.Count) Gradle files" }

# things the recipe depends on
$manifestPath = Join-Path $RepoRoot 'app/src/main/AndroidManifest.xml'
$manifest = Read-TextFile $manifestPath
if ($manifest -match '(?m)^[^\r\n]*REQUEST_INSTALL_PACKAGES[^\r\n]*\r?$') {
    $line = $Matches[0]
    if ($line -match '<uses-permission[^>]*/>') { Write-Ok 'REQUEST_INSTALL_PACKAGES sits on one line (recipe removes it with sed)' }
    else { Write-Warn2 'REQUEST_INSTALL_PACKAGES spans several lines - the recipe''s sed would break the manifest; adjust the prebuild step.' }
} else { Write-Info 'REQUEST_INSTALL_PACKAGES not in the manifest - the recipe''s sed is a harmless no-op.' }
$appGradle = Read-TextFile (Join-Path $RepoRoot 'app/build.gradle.kts')
if ($appGradle -notmatch 'isFdroidBuild' -or $appGradle -notmatch 'SELF_UPDATE') {
    $errors += 'app/build.gradle.kts has no F-Droid switch (fdroid property -> SELF_UPDATE=false).'
} else { Write-Ok 'Gradle F-Droid switch present (in-app updater off with fdroid=true)' }
if (-not (Test-Path (Join-Path $RepoRoot 'gradlew'))) { $errors += 'gradlew missing.' }

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Bad $_ }
    throw "$($errors.Count) blocking problem(s) found - fix them and run the script again."
}

# ---------------------------------------------------------------------------------------------
# 4. recipe
# ---------------------------------------------------------------------------------------------
Write-Step '4/9  Build recipe (fdroid/metadata)'
$lines = New-Object 'System.Collections.Generic.List[string]'
foreach ($l in ((Read-TextFile $RecipePath) -split "\r?\n")) { $lines.Add($l) }
while ($lines.Count -gt 0 -and $lines[$lines.Count - 1] -eq '') { $lines.RemoveAt($lines.Count - 1) }

$bIdx = $lines.IndexOf('Builds:')
if ($bIdx -lt 0) { throw "No 'Builds:' section in $RecipePath" }
$bEnd = $bIdx + 1
while ($bEnd -lt $lines.Count -and ($lines[$bEnd] -eq '' -or $lines[$bEnd] -match '^\s')) { $bEnd++ }
# The block ends at the first non-indented line (a comment or the next key); blank lines before it stay outside.
while ($bEnd -gt $bIdx + 1 -and $lines[$bEnd - 1] -eq '') { $bEnd-- }

$blockLines = @($lines.GetRange($bIdx + 1, $bEnd - $bIdx - 1))
$entries = @()
$cur = $null
foreach ($l in $blockLines) {
    if ($l -match '^  - versionName:') { if ($null -ne $cur) { $entries += ,$cur }; $cur = New-Object 'System.Collections.Generic.List[string]' }
    if ($null -ne $cur) { $cur.Add($l) }
}
if ($null -ne $cur) { $entries += ,$cur }
if ($entries.Count -eq 0) { throw "'Builds:' contains no '- versionName:' entry to use as a template." }

$template = @($entries[$entries.Count - 1])
$newEntry = @()
foreach ($l in $template) {
    $x = $l
    $x = $x -replace '^(  - versionName:).*$', "`$1 $VersionName"
    $x = $x -replace '^(    versionCode:).*$', "`$1 $VersionCode"
    $x = $x -replace '^(    commit:).*$', "`$1 $Tag"
    $newEntry += $x
}

$kept = @()
if ($KeepOldBuilds) {
    foreach ($e in $entries) {
        $code = ($e | Where-Object { $_ -match '^    versionCode:\s*(\d+)' } | ForEach-Object { $Matches[1] } | Select-Object -First 1)
        if ("$code" -ne "$VersionCode") { $kept += , @($e) }
    }
}
$newBlock = @()
foreach ($e in $kept) { $newBlock += $e }
$newBlock += $newEntry

$result = New-Object 'System.Collections.Generic.List[string]'
for ($i = 0; $i -le $bIdx; $i++) { $result.Add($lines[$i]) }
foreach ($l in $newBlock) { $result.Add($l) }
for ($i = $bEnd; $i -lt $lines.Count; $i++) { $result.Add($lines[$i]) }

for ($i = 0; $i -lt $result.Count; $i++) {
    if ($result[$i] -match '^CurrentVersion:')     { $result[$i] = "CurrentVersion: $VersionName" }
    if ($result[$i] -match '^CurrentVersionCode:') { $result[$i] = "CurrentVersionCode: $VersionCode" }
}
$newText = (($result -join "`n") + "`n")
if ($newText -ne ((Read-TextFile $RecipePath) -replace "`r`n", "`n")) {
    Write-TextFile $RecipePath $newText
    Write-Ok "Recipe updated: Builds -> $VersionName ($VersionCode, commit $Tag), CurrentVersion*"
} else { Write-Ok 'Recipe already up to date' }

# ---------------------------------------------------------------------------------------------
# 5. changelog
# ---------------------------------------------------------------------------------------------
Write-Step '5/9  Changelog for this version'
$changelogText = $null
foreach ($loc in $locales) {
    $file = Join-Path $loc.FullName "changelogs/$VersionCode.txt"
    if (Test-Path $file) {
        $bytes = [System.Text.Encoding]::UTF8.GetByteCount((Read-TextFile $file))
        if ($bytes -gt 500) { Write-Warn2 "$($loc.Name)\changelogs\$VersionCode.txt is $bytes bytes (max. 500)." }
        else { Write-Ok "$($loc.Name)\changelogs\$VersionCode.txt exists ($bytes bytes)" }
        continue
    }
    if ($null -eq $changelogText) {
        # previous release tag -> commit subjects since then
        $tags = @(Get-NativeOutput 'git' @('tag', '--list', 'v*', '--sort=-v:refname') $RepoRoot)
        $prev = $tags | Where-Object { $_ -and $_ -ne $Tag } | Select-Object -First 1
        $end  = 'HEAD'
        if (Get-NativeOutput 'git' @('rev-parse', '--verify', '--quiet', "refs/tags/$Tag") $RepoRoot) { $end = $Tag }
        $range = if ($prev) { "$prev..$end" } else { $end }
        $subjects = @(Get-NativeOutput 'git' @('log', '--no-merges', '--pretty=format:%s', $range) $RepoRoot) |
            Where-Object { $_ -and $_ -notmatch '^(Merge|F-Droid:)' }
        $sb = New-Object System.Text.StringBuilder
        foreach ($s in $subjects) {
            $candidate = "- $s`n"
            if ([System.Text.Encoding]::UTF8.GetByteCount($sb.ToString() + $candidate) -gt 480) { break }
            [void]$sb.Append($candidate)
        }
        $changelogText = $sb.ToString().TrimEnd()
        if (-not $changelogText) { $changelogText = "Version $VersionName" }
        Write-Info "Generated from git history ($range) - edit the files if you want nicer text."
    }
    Write-TextFile $file ($changelogText + "`n")
    Write-Ok "Created $($loc.Name)\changelogs\$VersionCode.txt"
}

# ---------------------------------------------------------------------------------------------
# 6. commit, tag, push (GitHub)
# ---------------------------------------------------------------------------------------------
Write-Step '6/9  Commit, release tag and what F-Droid will see'
if (-not (Test-Native 'git' @('rev-parse', '--is-inside-work-tree') $RepoRoot)) { throw "$RepoRoot is not a git repository." }

Invoke-Native 'git' @('add', '--', 'fdroid', 'fastlane', 'docs') $RepoRoot
if (-not (Test-Native 'git' @('diff', '--cached', '--quiet') $RepoRoot)) {
    if (Confirm-Action 'Commit the updated F-Droid files (fdroid/, fastlane/, docs/)?') {
        Invoke-Native 'git' @('commit', '-m', "F-Droid: metadata for $Tag ($VersionCode)") $RepoRoot
        Write-Ok 'Committed'
    } else { Write-Warn2 'F-Droid files are staged but not committed.' }
} else { Write-Ok 'No F-Droid file changes to commit' }

$dirty = Get-NativeOutput 'git' @('status', '--porcelain') $RepoRoot
if ($dirty) { Write-Warn2 'There are other uncommitted changes - they are NOT part of any tag or push.' }

if (-not $LocalOnly) {
    $null = Get-NativeOutput 'git' @('fetch', 'origin', '--tags', '--quiet') $RepoRoot
}
$tagLocal = Get-NativeOutput 'git' @('rev-parse', '--verify', '--quiet', "refs/tags/$Tag") $RepoRoot
if (-not $tagLocal -and -not $LocalOnly) {
    # The release workflow / GitHub release creates the tag on GitHub only. Look there explicitly
    # (the generic fetch above hides errors and can miss it) before offering to create a new tag.
    $remoteTagLine = Get-NativeOutput 'git' @('ls-remote', '--tags', 'origin', "refs/tags/$Tag") $RepoRoot
    if ($remoteTagLine) {
        Write-Info "Tag $Tag exists on GitHub (release) - fetching it."
        $null = Test-Native 'git' @('fetch', 'origin', "+refs/tags/${Tag}:refs/tags/${Tag}") $RepoRoot
        $tagLocal = Get-NativeOutput 'git' @('rev-parse', '--verify', '--quiet', "refs/tags/$Tag") $RepoRoot
    } elseif (Test-Cmd 'gh') {
        $relTag = Get-NativeOutput 'gh' @('release', 'view', $Tag, '--json', 'tagName', '--jq', '.tagName') $RepoRoot
        if ($relTag) { Write-Warn2 "A GitHub release '$Tag' exists but the tag is not visible via git (draft release? publish it first). Not creating a second tag." }
    }
}
if (-not $tagLocal) {
    Write-Info "Tag $Tag does not exist yet."
    if (Confirm-Action "Create tag $Tag on the current commit?") {
        Invoke-Native 'git' @('tag', '-a', $Tag, '-m', "Release $VersionName") $RepoRoot
        $tagLocal = Get-NativeOutput 'git' @('rev-parse', '--verify', '--quiet', "refs/tags/$Tag") $RepoRoot
        Write-Ok "Created tag $Tag"
    }
}
if ($tagLocal) {
    Write-Ok "Tag $Tag -> $(("$tagLocal").Substring(0, 8))"
    # What F-Droid builds is the TAG: it must contain the F-Droid switch and the store texts.
    $atTag = Get-NativeOutput 'git' @('show', "${Tag}:app/build.gradle.kts") $RepoRoot
    if (-not $atTag -or (($atTag -join "`n") -notmatch 'SELF_UPDATE')) {
        Write-Warn2 "Tag $Tag has NO F-Droid switch in app/build.gradle.kts: F-Droid would build the version WITH the self-updater. Release a newer version (bump dependencies.gradle) and re-run."
    } else { Write-Ok "Tag $Tag contains the F-Droid switch" }
    if (-not (Test-Native 'git' @('cat-file', '-e', "${Tag}:fastlane/metadata/android/en-US/title.txt") $RepoRoot)) {
        Write-Warn2 "Tag $Tag has no fastlane store texts - F-Droid would show no description for this version."
    }
    if (-not (Test-Native 'git' @('cat-file', '-e', "${Tag}:fastlane/metadata/android/en-US/changelogs/$VersionCode.txt") $RepoRoot)) {
        Write-Info "Tag $Tag has no changelogs\$VersionCode.txt (optional; add it BEFORE tagging the next release)."
    }
}

if (-not $LocalOnly -and -not $NoPush) {
    $branchName = (Get-NativeOutput 'git' @('rev-parse', '--abbrev-ref', 'HEAD') $RepoRoot)
    if (Confirm-Action "Push branch '$branchName' and tag '$Tag' to GitHub (origin)?") {
        Invoke-Native 'git' @('push', 'origin', 'HEAD') $RepoRoot
        if ($tagLocal) {
            $remoteTag = Get-NativeOutput 'git' @('ls-remote', '--tags', 'origin', "refs/tags/$Tag") $RepoRoot
            if (-not $remoteTag) { Invoke-Native 'git' @('push', 'origin', $Tag) $RepoRoot }
            else { Write-Ok "Tag $Tag already on GitHub" }
        }
    }
} else { Write-Info 'GitHub push skipped (-LocalOnly / -NoPush).' }

if ($LocalOnly) {
    Write-Step 'Done (-LocalOnly)'
    Write-Info 'Steps 7-9 (fdroiddata fork, build, merge request) were skipped.'
    if ($script:Warnings.Count -gt 0) { $script:Warnings | ForEach-Object { Write-Warn2 $_ } }
    try { Stop-Transcript | Out-Null } catch { }
    return
}

# ---------------------------------------------------------------------------------------------
# 7. fdroiddata fork + F-Droid build container
# ---------------------------------------------------------------------------------------------
Write-Step '7/9  fdroiddata fork, lint and build'
if (-not $GitLabUser) { $GitLabUser = (Read-Host '  Your GitLab username (owner of your fdroiddata fork)').Trim() }
if (-not $GitLabUser) { throw 'A GitLab username is required (-GitLabUser).' }

$fdData   = Join-Path $WorkDir 'fdroiddata'
$fdServer = Join-Path $WorkDir 'fdroidserver'

if (-not (Test-Path (Join-Path $fdData '.git'))) {
    Write-Info 'Opening the GitLab fork page. Fork fdroid/fdroiddata as a PUBLIC project under your account.'
    try { Start-Process 'https://gitlab.com/fdroid/fdroiddata/-/forks/new' } catch { Write-Info 'Open https://gitlab.com/fdroid/fdroiddata/-/forks/new manually.' }
    if (-not $Yes) { [void](Read-Host '  Press Enter when the fork exists') }
    Invoke-Native 'git' @('-c', 'core.autocrlf=false', 'clone', '--depth=1', "https://gitlab.com/$GitLabUser/fdroiddata.git", $fdData)
}
$remotes = @(Get-NativeOutput 'git' @('remote') $fdData)
if ($remotes -notcontains 'upstream') { Invoke-Native 'git' @('remote', 'add', 'upstream', 'https://gitlab.com/fdroid/fdroiddata.git') $fdData }
Invoke-Native 'git' @('fetch', '--depth=1', 'upstream', 'master') $fdData
# Always rebuild the branch from the CURRENT upstream/master. The branch only carries the single
# "New App" commit, which is recreated below from the recipe in this repo. A stale branch would
# carry old srclibs/ etc. and fail the checks of the (newer) fdroidserver.
Invoke-Native 'git' @('checkout', '-f', '-B', $Branch, 'upstream/master') $fdData
# Make sure the working tree has LF endings (an older clone made with core.autocrlf=true has CRLF,
# which makes 'fdroid readmeta' fail with "Invalid srclib metadata ... new-line-at-end-of-file").
Invoke-Native 'git' @('config', 'core.autocrlf', 'false') $fdData
$crlfProbe = Join-Path $fdData 'srclibs/MozAndroidComponentsAS.yml'
if ((Test-Path $crlfProbe) -and ((Read-TextFile $crlfProbe) -match "`r`n")) {
    Write-Info 'fdroiddata working tree has Windows line endings - re-checking out with LF.'
    Invoke-Native 'git' @('rm', '-r', '--cached', '-q', '.') $fdData
    Invoke-Native 'git' @('reset', '--hard', '-q') $fdData
}
$recipeLf = (Read-TextFile $RecipePath) -replace "`r`n", "`n"
Write-TextFile (Join-Path $fdData "metadata/${AppId}.yml") $recipeLf
Write-Ok "Recipe copied to fdroiddata\metadata\${AppId}.yml (branch $Branch)"

if ($SkipBuild) {
    Write-Info 'Container build skipped (-SkipBuild): the GitLab CI pipeline of your fork will lint and build after the push.'
} else {
    # Windows git (core.autocrlf=true) checks scripts out with CRLF -> "env: 'python3\r': No such file".
    # Clone with LF endings, and repair an existing clone that already has CRLF.
    $fdroidExe = Join-Path $fdServer 'fdroid'
    if ((Test-Path $fdroidExe) -and ((Read-TextFile $fdroidExe) -match "`r`n")) {
        Write-Info 'fdroidserver clone has Windows line endings - re-cloning with LF.'
        Remove-Item -Recurse -Force $fdServer
    }
    if (-not (Test-Path (Join-Path $fdServer '.git'))) {
        Invoke-Native 'git' @('-c', 'core.autocrlf=false', 'clone', '--depth=1', 'https://gitlab.com/fdroid/fdroidserver', $fdServer)
        Invoke-Native 'git' @('config', 'core.autocrlf', 'false') $fdServer
    }
    # The commands of F-Droid's "Submitting to F-Droid Quick Start Guide" (Test the metadata).
    $check = @'
#!/bin/bash
set -e
. /etc/profile
export PATH="$fdroidserver:$PATH" PYTHONPATH="$fdroidserver"
export JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 > /dev/null | grep 'java.home' | awk -F'=' '{print $2}' | tr -d ' ')
# The mounted folders belong to another uid: allow git to use them ("detected dubious ownership").
git config --global --add safe.directory '*' || true
# The Windows bind mount (/build) does not allow chmod/utime (rewritemeta, git clone in build/ fail with
# "Operation not permitted"), so work on a Linux-native copy and copy only the recipe back at the end.
rm -rf /tmp/work; mkdir -p /tmp/work
(cd /build && tar cf - --exclude=.git --exclude=.fdroid-setup-check.sh .) | tar xf - -C /tmp/work
chmod 600 /tmp/work/config.yml 2>/dev/null || true
cd /tmp/work
# Sanitize srclibs (CRLF from a Windows checkout / missing final newline) so the strict yaml check passes.
# Only the local working tree is touched; nothing of this is committed.
for f in srclibs/*.yml; do
  sed -i 's/\r$//' "$f" || true
  # Git symlinks (e.g. MozAndroidComponentsAS.yml -> MozAndroidComponents.yml) are checked out by
  # Windows git as small text files that only contain the target name: replace them by a copy of the target.
  if [ ! -L "$f" ] && [ "$(wc -l < "$f")" -le 1 ] && [ "$(wc -c < "$f")" -lt 120 ]; then
    target="$(tr -d '\r\n' < "$f")"
    if [ -n "$target" ] && [ -f "$(dirname "$f")/$target" ] && [ "$target" != "$(basename "$f")" ]; then
      echo "restoring symlink $f -> $target"
      cp -f "$(dirname "$f")/$target" "$f" || true
    fi
  fi
  if [ -s "$f" ] && [ -n "$(tail -c1 "$f")" ]; then echo >> "$f" || true; fi
done
echo "srclib probe (tail of MozAndroidComponentsAS.yml):"; od -c srclibs/MozAndroidComponentsAS.yml 2>/dev/null | tail -3 || true
fdroid readmeta
fdroid rewritemeta __APPID__
cat /tmp/work/metadata/__APPID__.yml > /build/metadata/__APPID__.yml
fdroid checkupdates --allow-dirty __APPID__ || echo "checkupdates reported problems (expected: versionCode is computed in Gradle, so auto-update stays off)"
fdroid lint __APPID__
fdroid build -v -l __APPID__
'@
    $check = ($check.Replace('__APPID__', $AppId)) -replace "`r`n", "`n"
    $helper = Join-Path $fdData '.fdroid-setup-check.sh'
    Write-TextFile $helper ($check + "`n")
    $ranBuild = $true
    Write-Info 'Running readmeta / rewritemeta / checkupdates / lint / build in the F-Droid build-server container.'
    Write-Info '(First run downloads ~1 GB and the build takes a while - this is the same check F-Droid does.)'
    try {
        Invoke-Native 'docker' @('run', '--rm', '-u', 'vagrant', '--entrypoint', '/bin/bash',
            '-v', "${fdData}:/build",
            '-v', "${fdServer}:/home/vagrant/fdroidserver",
            'registry.gitlab.com/fdroid/fdroidserver:buildserver',
            '/build/.fdroid-setup-check.sh')
        Write-Ok 'fdroid lint + fdroid build passed'
    } finally {
        Remove-Item -Force -ErrorAction SilentlyContinue $helper
    }
    # rewritemeta writes the canonical form - keep the repository copy identical to what gets submitted.
    $canonical = Join-Path $fdData "metadata/${AppId}.yml"
    if ((Read-TextFile $canonical) -ne (Read-TextFile $RecipePath)) {
        Copy-Item -Path $canonical -Destination $RecipePath -Force
        Write-Ok 'Repository recipe updated to the canonical form written by rewritemeta (commit it with the next change).'
    }
}

Invoke-Native 'git' @('add', "metadata/${AppId}.yml") $fdData
if (-not (Test-Native 'git' @('diff', '--cached', '--quiet') $fdData)) {
    Invoke-Native 'git' @('commit', '-m', "New App: $AppId") $fdData
    Write-Ok "Committed 'New App: $AppId' in fdroiddata"
} else { Write-Ok 'fdroiddata branch already contains the current recipe' }

$pushed = $false
if ($NoPush) { Write-Info 'GitLab push skipped (-NoPush).' }
elseif (Confirm-Action "Push branch '$Branch' to your fork (gitlab.com/$GitLabUser/fdroiddata)?") {
    if (-not (Test-Native 'git' @('push', '-u', '--force-with-lease', 'origin', $Branch) $fdData)) {
        # "shallow update not allowed": the depth-1 clone sits on an upstream commit that your (older) fork
        # does not have. Rebuild the branch on top of YOUR FORK's master (GitLab already has that commit, so a
        # shallow push is accepted): it then holds just the one "New App" commit. The merge request against
        # fdroid/fdroiddata still shows only that commit, because your fork's master is an ancestor of upstream's.
        Write-Info 'Push was rejected (shallow clone). Re-basing the New App commit onto your fork''s master and retrying ...'
        $recipeKeep = Read-TextFile (Join-Path $fdData "metadata/${AppId}.yml")
        Invoke-Native 'git' @('fetch', '--depth=1', 'origin', 'master') $fdData
        Invoke-Native 'git' @('checkout', '-f', '-B', $Branch, 'FETCH_HEAD') $fdData
        Write-TextFile (Join-Path $fdData "metadata/${AppId}.yml") (($recipeKeep) -replace "`r`n", "`n")
        Invoke-Native 'git' @('add', "metadata/${AppId}.yml") $fdData
        Invoke-Native 'git' @('commit', '-m', "New App: $AppId") $fdData
        Invoke-Native 'git' @('push', '-u', '--force', 'origin', $Branch) $fdData
    }
    Write-Ok 'Push command finished'
}
# Verify on GitLab itself - the merge request form only works when the branch really exists there.
$remoteHead = Get-NativeOutput 'git' @('ls-remote', '--heads', 'origin', $Branch) $fdData
if ($remoteHead) {
    $pushed = $true
    Write-Ok "Branch '$Branch' exists on GitLab (gitlab.com/$GitLabUser/fdroiddata)"
} else {
    Write-Bad "Branch '$Branch' is NOT on GitLab - the merge request cannot be created yet (push was declined or skipped)."
    Write-Info "Push it manually:  git -C `"$fdData`" push -u --force origin $Branch"
    $script:Warnings += "Branch $Branch is not on your GitLab fork - push it, then open the merge request."
}

# ---------------------------------------------------------------------------------------------
# 8. merge request
# ---------------------------------------------------------------------------------------------
Write-Step '8/9  Merge request'
$mrTitle = 'New app: A*Untis'
if ($ranBuild) {
    $buildLine  = '- [x] Builds with fdroid build (-v -l, F-Droid build-server container)'
    $checksLine = 'Checks run locally in the build-server container: fdroid readmeta, rewritemeta, checkupdates, lint, build -v -l'
} else {
    $buildLine  = '- [ ] Builds with fdroid build - not run locally, see the pipelines of this merge request'
    $checksLine = 'The pipelines of this merge request run lint and build.'
}
$mrBody = @"
New app: A*Untis ($AppId) - unofficial WebUntis client for students and parents.

Required
- [x] The app complies with the inclusion criteria (GPL-3.0, no proprietary dependencies)
- [x] The original app author has been notified - I am the author
- [x] All related fdroiddata and RFP issues have been referenced in this merge request - there are none
$buildLine

About the app
- Source: https://github.com/Ub1Catcrush/a_untis (tag $Tag, versionCode $VersionCode)
- AntiFeatures: NonFreeNet - the app only works with a WebUntis school account (proprietary online service).
- Store texts and screenshots come from fastlane/metadata/android in the app repository.
- The F-Droid build disables the in-app GitHub updater (gradle property fdroid=true) and removes REQUEST_INSTALL_PACKAGES.
- versionCode is computed in Gradle (major*1000000 + minor*10000 + patch), so Builds entries are added per release; AutoUpdateMode is None.

$checksLine
"@
try { Set-Clipboard -Value $mrBody; Write-Ok 'MR description copied to the clipboard.' } catch { Write-Info 'Clipboard not available - copy the text below.' }
Write-Host ''
Write-Host "  Title: $mrTitle" -ForegroundColor White
Write-Host $mrBody
$mrUrl = "https://gitlab.com/$GitLabUser/fdroiddata/-/merge_requests/new?merge_request%5Bsource_branch%5D=$Branch"
if ($pushed) {
    Write-Info 'Opening the merge-request page: target must be fdroid/fdroiddata, branch master; paste title + description.'
    try { Start-Process $mrUrl } catch { Write-Info "Open: $mrUrl" }
} else {
    Write-Info "After pushing the branch, open: $mrUrl"
}

# ---------------------------------------------------------------------------------------------
# 9. optional: own F-Droid repository
# ---------------------------------------------------------------------------------------------
if ($SetupOwnRepo) {
    Write-Step '9/9  Own F-Droid repository (GitHub Pages)'
    if (-not (Test-Native 'gh' @('auth', 'status'))) { throw "GitHub CLI is not logged in: run 'gh auth login' first." }

    $keyDir  = Join-Path $WorkDir 'repo-key'
    $ks      = Join-Path $keyDir 'fdroid-repo.jks'
    $pwFile  = Join-Path $keyDir 'fdroid-repo-password.txt'
    New-Item -ItemType Directory -Force -Path $keyDir | Out-Null
    if (-not (Test-Path $ks)) {
        $chars = (48..57) + (65..90) + (97..122)
        $pw = -join (1..28 | ForEach-Object { [char]($chars | Get-Random) })
        Invoke-Native 'keytool' @('-genkeypair', '-v', '-keystore', $ks, '-alias', 'fdroid-repo', '-keyalg', 'RSA', '-keysize', '4096',
            '-validity', '10000', '-storepass', $pw, '-keypass', $pw, '-dname', 'CN=A*Untis F-Droid repo, O=A*Untis')
        Write-TextFile $pwFile $pw
        Write-Ok "Created repo signing key: $ks"
        Write-Warn2 "BACK UP $keyDir (key + password). Losing it means everybody has to re-add the repository."
    } else { Write-Ok "Reusing existing repo key: $ks" }
    $pw = (Read-TextFile $pwFile).Trim()
    $b64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($ks))

    if (Confirm-Action 'Set the 4 GitHub secrets (FDROID_KEYSTORE, FDROID_KEYSTORE_PASSWORD, FDROID_KEY_PASSWORD, FDROID_KEY_ALIAS)?') {
        Push-Location $RepoRoot
        try {
            $b64 | & gh secret set FDROID_KEYSTORE
            if ($LASTEXITCODE -ne 0) { throw 'gh secret set FDROID_KEYSTORE failed' }
            $pw | & gh secret set FDROID_KEYSTORE_PASSWORD
            if ($LASTEXITCODE -ne 0) { throw 'gh secret set FDROID_KEYSTORE_PASSWORD failed' }
            $pw | & gh secret set FDROID_KEY_PASSWORD
            if ($LASTEXITCODE -ne 0) { throw 'gh secret set FDROID_KEY_PASSWORD failed' }
            'fdroid-repo' | & gh secret set FDROID_KEY_ALIAS
            if ($LASTEXITCODE -ne 0) { throw 'gh secret set FDROID_KEY_ALIAS failed' }
        } finally { Pop-Location }
        Write-Ok 'GitHub secrets set'
        if (Confirm-Action "Run the 'Publish F-Droid repository' workflow for $Tag now?") {
            Invoke-Native 'gh' @('workflow', 'run', 'fdroid.yml', '-f', "tag=$Tag") $RepoRoot
            Write-Ok 'Workflow started (Actions tab). It creates the gh-pages branch.'
        }
        Write-Info 'Then enable GitHub Pages: Settings -> Pages -> Deploy from a branch -> gh-pages / (root).'
        Write-Info 'Repository URL for the F-Droid app: https://<owner>.github.io/<repo>/fdroid/repo'
        Write-Info "Fingerprint to compare in the app:  keytool -list -v -keystore `"$ks`""
    }
} else {
    Write-Step '9/9  Own F-Droid repository'
    Write-Info 'Skipped (add -SetupOwnRepo to create the repo key and the GitHub secrets).'
}

# ---------------------------------------------------------------------------------------------
# summary
# ---------------------------------------------------------------------------------------------
Write-Step 'Summary'
Write-Ok "Recipe     : fdroid\metadata\${AppId}.yml  ($VersionName / $VersionCode / $Tag)"
Write-Ok "fdroiddata : $fdData (branch $Branch)"
if ($script:Warnings.Count -gt 0) {
    Write-Host ''
    Write-Host '  Warnings to look at:' -ForegroundColor Yellow
    $script:Warnings | ForEach-Object { Write-Host "   - $_" -ForegroundColor Yellow }
}
Write-Host ''
Write-Host '  Left for you: create the merge request on GitLab (title + description are in the clipboard)' -ForegroundColor Cyan
Write-Host '  and answer the F-Droid reviewers. For every new release: bump dependencies.gradle, release,' -ForegroundColor Cyan
Write-Host '  then run this script again (it adds the new Builds entry; see docs/FDROID.md).' -ForegroundColor Cyan
try { Stop-Transcript | Out-Null } catch { }
