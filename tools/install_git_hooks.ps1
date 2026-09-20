<#
  Install (or refresh) the git pre-commit gate hook for this repository.

  Why: .git/ is not versioned, so hooks cannot ship with the repo. Re-run this
  script after a fresh clone or on a new machine.

  What it does: every `git commit` first runs `node tools/pre_push_check.js`,
  which refuses the commit if the staged files contain credentials, private
  keys, or absolute paths leaking this machine's user name.
  Emergency bypass: `git commit --no-verify`

  Usage (this machine blocks .ps1 by execution policy, so Bypass is required):
      powershell -ExecutionPolicy Bypass -File tools/install_git_hooks.ps1

  NOTE: this file must stay ASCII-only. Windows PowerShell 5.1 is the only
  PowerShell on this machine and it decodes BOM-less .ps1 files using the system
  ANSI code page (GBK here), which mangles non-ASCII text and can break parsing.
#>
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$hooks = Join-Path $repo '.git\hooks'
if (-not (Test-Path $hooks)) { throw "Not a git repository root: $repo" }

$hookPath = Join-Path $hooks 'pre-commit'
$lines = @(
    '#!/bin/sh',
    '# pingpong-mod pre-commit gate (installed by tools/install_git_hooks.ps1)',
    '#',
    '# Runs tools/pre_push_check.js on the staged files: refuses the commit when',
    '# credentials, private keys or absolute paths are about to enter the repo.',
    '# Bypass: git commit --no-verify',
    '# Uninstall: delete this file.',
    '',
    'exec node "$(git rev-parse --show-toplevel)/tools/pre_push_check.js"',
    ''
)
# The hook itself is a sh script: it must use LF endings and no BOM.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($hookPath, ($lines -join "`n"), $utf8NoBom)

Write-Host "pre-commit hook installed: $hookPath"
Write-Host "verify: node tools/pre_push_check.js --all"
Write-Host "bypass: git commit --no-verify"
