<#
  通用「可重入续传下载」工具（M5 用）。

  【为什么要这个脚本】mc-pingpong 的 1.16.5 目标卡在下载：
    - `gradlew` 自己下不动：Java 走 Steam++ 代理时报
      `PKIX path building failed: unable to find valid certification path`（Java 不信任代理证书链）；
    - PowerShell 的 Invoke-WebRequest / HttpWebRequest 能过；
    - 但服务端时通时不通，一次下 117MB 基本会中途断。

  所以做成：**按 4MB 分块 + 每块独立重试 + 断点续传**。断了再跑一次就接着下，
  不会从头开始（已下好的字节直接复用）。

  用法（必须带 Bypass，本机执行策略禁止直接跑 .ps1）：
      powershell -ExecutionPolicy Bypass -File tools/resume_download.ps1 `
          -Url "https://services.gradle.org/distributions/gradle-7.6.4-bin.zip" `
          -OutFile "C:\Users\计算机\.gradle\wrapper\dists\gradle-7.6.4-bin\f8bb1ab0fb9d2ec84544a03e285572b1\gradle-7.6.4-bin.zip"

  幂等：文件已完整（>= -ExpectedBytes 或服务端返回 416）时直接跳过。
#>
param(
	[Parameter(Mandatory = $true)][string]$Url,
	[Parameter(Mandatory = $true)][string]$OutFile,
	[long]$ExpectedBytes = 0,          # 已知总大小时填；0 = 让服务端告诉我们
	[int]$ChunkBytes = 4MB,            # 每块大小（4MB 比较耐受抖动）
	[int]$MaxRetries = 8,              # 每块最多重试次数
	[int]$RetryDelaySec = 6
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$dir = Split-Path -Parent $OutFile
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }

function Get-RemoteLength([string]$url) {
	$req = [System.Net.HttpWebRequest]::Create($url)
	$req.Method = 'HEAD'
	$req.Timeout = 60000
	$resp = $req.GetResponse()
	$len = $resp.ContentLength
	$resp.Close()
	return $len
}

if ($ExpectedBytes -le 0) {
	try {
		$ExpectedBytes = Get-RemoteLength $Url
		Write-Host "服务端报告大小：$([math]::Round($ExpectedBytes/1MB,1)) MB"
	} catch {
		Write-Host "HEAD 拿不到大小（$($_.Exception.Message)），将按流式写入"
	}
}

$have = if (Test-Path $OutFile) { (Get-Item $OutFile).Length } else { 0 }
Write-Host "开始：已有 $([math]::Round($have/1MB,2)) MB" + $(if ($ExpectedBytes -gt 0) { " / 共 $([math]::Round($ExpectedBytes/1MB,1)) MB" } else { "" })

if ($ExpectedBytes -gt 0 -and $have -ge $ExpectedBytes) {
	Write-Host "✅ 已是完整文件，无需下载。"
	exit 0
}

$round = 0
while ($true) {
	$round++
	$have = if (Test-Path $OutFile) { (Get-Item $OutFile).Length } else { 0 }
	if ($ExpectedBytes -gt 0 -and $have -ge $ExpectedBytes) { break }

	$end = if ($ExpectedBytes -gt 0) { [Math]::Min($have + $ChunkBytes - 1, $ExpectedBytes - 1) } else { $have + $ChunkBytes - 1 }
	$ok = $false
	for ($i = 1; $i -le $MaxRetries -and -not $ok; $i++) {
		try {
			$req = [System.Net.HttpWebRequest]::Create($Url)
			$req.Timeout = 120000
			$req.ReadWriteTimeout = 120000
			$req.AddRange($have, $end)
			$resp = $req.GetResponse()
			$in = $resp.GetResponseStream()
			$fs = [System.IO.File]::Open($OutFile, [System.IO.FileMode]::Append)
			$buf = New-Object byte[] 262144
			$got = 0
			try {
				while (($n = $in.Read($buf, 0, $buf.Length)) -gt 0) {
					$fs.Write($buf, 0, $n)
					$got += $n
				}
			} finally {
				$fs.Close(); $in.Close(); $resp.Close()
			}
			$ok = $got -gt 0
			if (-not $ok) { throw "本块未读到数据" }
		} catch {
			$msg = $_.Exception.Message
			if ($msg -match '416|Requested Range Not Satisfiable') {
				# 已经下到底了
				$ok = $true
				if ($ExpectedBytes -le 0) { $ExpectedBytes = (Get-Item $OutFile).Length }
				break
			}
			Write-Host ("  第 {0} 次尝试失败：{1}" -f $i, $msg.Substring(0, [Math]::Min(70, $msg.Length)))
			Start-Sleep -Seconds $RetryDelaySec
		}
	}
	if (-not $ok) {
		Write-Host "❌ 连续 $MaxRetries 次都失败，停在 $([math]::Round((Get-Item $OutFile).Length/1MB,2)) MB。稍后重跑本脚本即可续传。"
		exit 1
	}
	if ($round % 5 -eq 0) {
		Write-Host "  ...已下 $([math]::Round((Get-Item $OutFile).Length/1MB,2)) MB"
	}
}

$final = (Get-Item $OutFile).Length
Write-Host "✅ 完成：$([math]::Round($final/1MB,2)) MB → $OutFile"
exit 0
