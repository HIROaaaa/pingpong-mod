<#
  把 mc-pingpong 的版本线收口到单个 v1.9.0（用户 2026-09-21 拍板）。

  做的事：
    1. 删远端 Release：v1.9.1 ~ v1.9.11（逐个 tag 精确取对象再删，**不做数组过滤**）
    2. 删远端 tag：v1.9.1 ~ v1.9.11
    3. 删 v1.9.0 的旧 tag（原指 4ffc9a7，是 1.9.0 刚开线时的提交，不是同一棵树），
       以当前 HEAD 重建并强推
    4. push main
    5. 调 publish_release.ps1 更新 v1.9.0 的 Release（正文取 CHANGELOG 的 1.9.0 小节，附件换成新 jar）

  【为什么不写成数组过滤】上一版用 `@(Invoke-RestMethod .../releases)` 再 `Where-Object` 过滤，
  在 PowerShell 5.1 下 `$_` / `$_.tag_name` 会退化到整个属性数组（打印出 21 个 tag 串在一起，
  看起来像"要删全部 Release"），`$r.id` 也可能变成 id 数组 → URL 拼成非法值而静默失败。
  教训：**批量删除绝不要依赖管道里的 $_，改成显式清单逐个精确执行。**

  用法（本机执行策略禁止直接跑 .ps1，必须带 Bypass）：
      powershell -ExecutionPolicy Bypass -File tools/reset_releases_190.ps1 -Yes
      不带 -Yes 只打印计划。
#>
param(
	[string]$Repo = 'HIROaaaa/pingpong-mod',
	[string]$Keep = 'v1.9.0',
	[switch]$Yes
)

$ErrorActionPreference = 'Stop'
$env:GIT_TERMINAL_PROMPT = '0'
$ProgressPreference = 'SilentlyContinue'
Set-Location (Join-Path $PSScriptRoot '..')

# ---- 凭据 ----
$cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
$token = (($cred -split "`n" | Where-Object { $_ -like 'password=*' }) -replace '^password=', '').Trim()
if (-not $token) { throw 'git 凭据管理器里没有 github.com 的 token' }
$headers = @{
	Authorization          = "Bearer $token"
	Accept                 = 'application/vnd.github+json'
	'User-Agent'           = 'pingpong-reset'
	'X-GitHub-Api-Version' = '2022-11-28'
}
$api = "https://api.github.com/repos/$Repo"

# ---- 显式清单（要删的中间版本，硬编码，避免任何过滤歧义）----
$doomedTags = @('v1.9.1', 'v1.9.2', 'v1.9.3', 'v1.9.4', 'v1.9.5', 'v1.9.6',
	'v1.9.7', 'v1.9.8', 'v1.9.9', 'v1.9.10', 'v1.9.11')

$remoteTags = @(cmd /c "git ls-remote --tags --refs origin 2>nul" | ForEach-Object { ($_ -split "`t")[1] -replace '^refs/tags/', '' })
Write-Host '=== 当前远端 tag ==='
$remoteTags | ForEach-Object { Write-Host "  $_" }
$remoteReleases = @(Invoke-RestMethod -Uri "$api/releases?per_page=100" -Headers $headers)
Write-Host "=== 当前 Release 数：$($remoteReleases.Count) ==="
$remoteReleases | ForEach-Object { Write-Host "  $($_.tag_name)" }

# 执行前守卫：只许删清单内的东西
foreach ($t in $doomedTags) {
	if ($t -notmatch '^v1\.9\.\d+$') { throw "清单里有非法 tag：$t" }
}
$safeToDelete = @($doomedTags | Where-Object { $remoteTags -contains $_ })
Write-Host ''
Write-Host ("将删除的 tag/Release：" + ($safeToDelete -join ', '))
Write-Host "将重建并强推 $Keep -> $(git rev-parse --short HEAD)"

if (-not $Yes) { Write-Host ''; Write-Host '（未带 -Yes，只做计划）'; return }

# ---- 1+2. 逐个精确删除（tag 与 Release）----
foreach ($tag in $safeToDelete) {
	$rel = $null
	try { $rel = Invoke-RestMethod -Uri "$api/releases/tags/$tag" -Headers $headers } catch { $rel = $null }
	if ($rel -and $rel.tag_name -eq $tag) {
		Invoke-RestMethod -Method Delete -Uri "$api/releases/$($rel.id)" -Headers $headers | Out-Null
		Write-Host "  已删 Release $tag (id=$($rel.id))"
	} else {
		Write-Host "  跳过 Release $tag（不存在）"
	}
	cmd /c "git push origin --delete $tag 2>&1" | Out-Null
	if ($LASTEXITCODE -ne 0) { throw "删除远端 tag 失败：$tag" }
	Write-Host "  已删远端 tag $tag"
}

# ---- 3+4. 重建 tag 并推 main ----
git tag -d $Keep 2>$null | Out-Null
git tag -a $Keep -m "$Keep（动作 M6 + 球拍 M9 合并版，1.9.1~1.9.24 撤下）"
cmd /c "git push origin main 2>&1"
if ($LASTEXITCODE -ne 0) { throw 'push main 失败' }
cmd /c "git push --force origin $Keep 2>&1"
if ($LASTEXITCODE -ne 0) { throw "强推 $Keep 失败" }
Write-Host "  已推送 main 与 $Keep"

# ---- 5. 发/更新 Release ----
& (Join-Path $PSScriptRoot 'publish_release.ps1') -Tag $Keep -Jar "build/libs/pingpong-$($Keep -replace '^v','').jar" -Title "$Keep — 动作系统与球拍建模合并版"

Write-Host ''
Write-Host '=== 收口后 ==='
$after = @(Invoke-RestMethod -Uri "$api/releases?per_page=100" -Headers $headers)
$after | ForEach-Object { Write-Host "  Release $($_.tag_name)" }
$afterTags = @(git ls-remote --tags --refs origin 2>$null | ForEach-Object { ($_ -split "`t")[1] -replace '^refs/tags/', '' })
Write-Host '  远端 tag：'
$afterTags | ForEach-Object { Write-Host "    $_" }
