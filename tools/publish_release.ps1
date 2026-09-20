<#
  发布一个 GitHub Release，并把对应版本的 mod jar 作为附件上传。

  用法（本机 PowerShell 执行策略禁止直接跑 .ps1，必须带 Bypass）：
      pwsh -ExecutionPolicy Bypass -File tools/publish_release.ps1 `
           -Tag v1.2.0 -Jar build/libs/pingpong-1.2.0.jar

  前置条件：
    1. gradle.properties 里的 mod_version 已改成对应版本，且 CHANGELOG.md 已写好该版小节；
    2. 改动已提交，并且标签已经推到远端：git tag -a v1.2.0 -m "..." ; git push origin v1.2.0

  说明：
    - Release 正文默认从 CHANGELOG.md 里抽取 `## [x.y.z]` 那一节，可用 -NotesFile 指定其它文件；
    - 凭据从 git 凭据管理器读取（本机已存 GitHub token），脚本既不落盘也不打印它；
    - 本机 GitHub 走 Steam++ 加速，HTTPS 可用、SSH 22 不通，所以远端必须是 HTTPS。
#>
param(
	[Parameter(Mandatory = $true)][string]$Tag,
	[Parameter(Mandatory = $true)][string]$Jar,
	[string]$Title,
	[string]$NotesFile = 'CHANGELOG.md',
	[string]$Repo = 'HIROaaaa/pingpong-mod'
)

$ErrorActionPreference = 'Stop'
$env:GIT_TERMINAL_PROMPT = '0'

if (-not (Test-Path $Jar)) { throw "找不到 jar：$Jar" }

# ---- 取凭据（不打印、不落盘）----
$cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
$token = (($cred -split "`n" | Where-Object { $_ -like 'password=*' }) -replace '^password=', '').Trim()
if (-not $token) { throw 'git 凭据管理器里没有 github.com 的 token，先在浏览器/客户端登录一次 GitHub' }

$headers = @{
	Authorization            = "Bearer $token"
	Accept                   = 'application/vnd.github+json'
	'User-Agent'             = 'pingpong-release'
	'X-GitHub-Api-Version'   = '2022-11-28'
}
$api = "https://api.github.com/repos/$Repo"

# ---- 正文：从 CHANGELOG 里抽这一版的小节 ----
$version = $Tag -replace '^v', ''
$notes = ''
if (Test-Path $NotesFile) {
	# 【必须带 -Encoding UTF8】本文件是 UTF-8，而 PowerShell 5.1 的 Get-Content 默认按系统
	# ANSI 代码页（中文机器是 GBK）读取，会把「三」的 UTF-8 字节 E4B889 读成「涓」，
	# 于是 Release 正文被双重编码成乱码（v1.2.0 首次发布就这样翻过车）。
	$lines = Get-Content -Encoding UTF8 $NotesFile
	$start = ($lines | Select-String -Pattern "^##\s*\[?$([regex]::Escape($version))\]?" | Select-Object -First 1).LineNumber
	if ($start) {
		$rest = $lines[$start..($lines.Count - 1)]
		$end = ($rest | Select-String -Pattern '^##\s' | Select-Object -First 1).LineNumber
		$notes = ($(if ($end) { $rest[0..($end - 2)] } else { $rest }) -join "`n")
	}
}
if (-not $notes) { $notes = "版本 $version" }
if (-not $Title) { $Title = "$Tag — $Repo" }

Write-Host "发布 $Tag（$([math]::Round((Get-Item $Jar).Length / 1KB))KB）…"

$body = @{ tag_name = $Tag; name = $Title; body = $notes; draft = $false; prerelease = $false } | ConvertTo-Json -Depth 5
$bytes = [System.Text.Encoding]::UTF8.GetBytes($body)

# 已存在则更新（幂等）：重复跑不会 422，也方便修正文
$existing = $null
try { $existing = Invoke-RestMethod -Uri "$api/releases/tags/$Tag" -Headers $headers } catch { $existing = $null }
if ($existing) {
	$release = Invoke-RestMethod -Method Patch -Uri "$api/releases/$($existing.id)" -Headers $headers -Body $bytes -ContentType 'application/json; charset=utf-8'
	Write-Host "♻️ Release 已存在，已更新正文：$($release.html_url)"
} else {
	$release = Invoke-RestMethod -Method Post -Uri "$api/releases" -Headers $headers -Body $bytes -ContentType 'application/json; charset=utf-8'
	Write-Host "✅ Release 已创建：$($release.html_url)"
}

$fileName = Split-Path $Jar -Leaf
$sameName = $release.assets | Where-Object { $_.name -eq $fileName } | Select-Object -First 1
if ($sameName) {
	Invoke-RestMethod -Method Delete -Uri "$api/releases/assets/$($sameName.id)" -Headers $headers | Out-Null
	Write-Host "（同名旧附件已删除，重新上传）"
}

$uploadUrl = "https://uploads.github.com/repos/$Repo/releases/$($release.id)/assets?name=$([uri]::EscapeDataString($fileName))"
$asset = Invoke-RestMethod -Method Post -Uri $uploadUrl -Headers $headers -InFile $Jar -ContentType 'application/java-archive'
Write-Host "📦 附件：$($asset.name)  $([math]::Round($asset.size / 1KB))KB"
