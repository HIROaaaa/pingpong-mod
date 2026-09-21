# pp_github_190.ps1 -- GitHub-side consolidation for mc-pingpong v1.9.0
#
# NOTE: keep this file pure ASCII. PowerShell 5.1 reads .ps1 without a BOM as GBK,
# so any non-ASCII literal here would break parsing. Data comes in via env vars/files.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools/pp_github_190.ps1 -Action release-info -Tag v1.9.0
#   powershell -ExecutionPolicy Bypass -File tools/pp_github_190.ps1 -Action delete-tag -Tag v1.9.1
#   powershell -ExecutionPolicy Bypass -File tools/pp_github_190.ps1 -Action patch-release -ReleaseId 123 -BodyFile <json> -Asset <jar>
#   powershell -ExecutionPolicy Bypass -File tools/pp_github_190.ps1 -Action create-tag -Tag v1.9.0 -Sha <sha>
#   powershell -ExecutionPolicy Bypass -File tools/pp_github_190.ps1 -Action list
param(
	[Parameter(Mandatory = $true)][string]$Action,
	[string]$Tag,
	[string]$Sha,
	[int]$ReleaseId,
	[string]$BodyFile,
	[string]$Asset,
	[string]$Repo = 'HIROaaaa/pingpong-mod'
)

$ErrorActionPreference = 'Stop'
$env:GIT_TERMINAL_PROMPT = '0'
$ProgressPreference = 'SilentlyContinue'

$cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
$token = (($cred -split "`n" | Where-Object { $_ -like 'password=*' }) -replace '^password=', '').Trim()
if (-not $token) { throw 'no github token in credential manager' }

$H = @{
	Authorization          = "Bearer $token"
	Accept                 = 'application/vnd.github+json'
	'User-Agent'           = 'pingpong'
	'X-GitHub-Api-Version' = '2022-11-28'
}
$api = "https://api.github.com/repos/$Repo"

function Invoke-Api($Method, $Url, $Body) {
	$p = @{ Method = $Method; Uri = $Url; Headers = $H }
	if ($Body) { $p.Body = $Body; $p.ContentType = 'application/json; charset=utf-8' }
	try { return Invoke-RestMethod @p } catch {
		$code = $_.Exception.Response.StatusCode.value__
		if ($code -eq 404) { return $null }
		throw
	}
}

switch ($Action) {
	'list' {
		$rels = Invoke-Api GET "$api/releases?per_page=100"
		Write-Output ('RELEASES:' + (@($rels) | ForEach-Object { $_.tag_name }) -join ',')
		$refs = Invoke-Api GET "$api/git/refs/tags"
		Write-Output ('TAGS:' + (@($refs) | ForEach-Object { $_.ref -replace 'refs/tags/', '' }) -join ',')
	}
	'tag-info' {
		$r = Invoke-Api GET "$api/git/ref/tags/$Tag"
		if ($r) { Write-Output ("SHA=" + $r.object.sha) } else { Write-Output 'SHA=' }
	}
	'delete-tag' {
		$r = Invoke-Api DELETE "$api/git/refs/tags/$Tag"
		Write-Output "DELETED_TAG=$Tag"
	}
	'create-tag' {
		$body = @{ ref = "refs/tags/$Tag"; sha = $Sha } | ConvertTo-Json
		$r = Invoke-Api POST "$api/git/refs" $body
		Write-Output ("CREATED_TAG=" + $r.ref + " SHA=" + $r.object.sha)
	}
	'release-info' {
		$r = Invoke-Api GET "$api/releases/tags/$Tag"
		if ($r) {
			Write-Output ("ID=" + $r.id)
			Write-Output ("TARGET=" + $r.target_commitish)
			Write-Output ("ASSETS=" + ((@($r.assets) | ForEach-Object { "$($_.id):$($_.name)" }) -join ','))
			$payload = @{
				id = $r.id
				tag_name = $r.tag_name
				name = $r.name
				body = $r.body
				created_at = $r.created_at
				assets = @($r.assets) | ForEach-Object { @{ id = $_.id; name = $_.name; size = $_.size; created_at = $_.created_at } }
			}
			$json = $payload | ConvertTo-Json -Depth 6 -Compress
			$b64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($json))
			Write-Output ("B64=" + $b64)
		} else { Write-Output 'ID=' }
	}
	'delete-release' {
		$null = Invoke-Api DELETE "$api/releases/$ReleaseId"
		Write-Output "DELETED_RELEASE=$ReleaseId"
	}
	'patch-release' {
		$json = [System.IO.File]::ReadAllText($BodyFile, [System.Text.Encoding]::UTF8)
		$r = Invoke-Api PATCH "$api/releases/$ReleaseId" $json
		Write-Output ("PATCHED=" + $r.id + " name=" + $r.name)
	}
	'create-release' {
		$json = [System.IO.File]::ReadAllText($BodyFile, [System.Text.Encoding]::UTF8)
		$r = Invoke-Api POST "$api/releases" $json
		Write-Output ("CREATED=" + $r.id + " tag=" + $r.tag_name)
	}
	'delete-asset' {
		$null = Invoke-Api DELETE "$api/releases/assets/$ReleaseId"
		Write-Output "DELETED_ASSET=$ReleaseId"
	}
	'upload-asset' {
		$bytes = [System.IO.File]::ReadAllBytes($Asset)
		$name = [uri]::EscapeDataString((Split-Path $Asset -Leaf))
		$url = "https://uploads.github.com/repos/$Repo/releases/$ReleaseId/assets?name=$name"
		$r = Invoke-RestMethod -Method POST -Uri $url -Headers $H -Body $bytes -ContentType 'application/java-archive'
		Write-Output ("UPLOADED=" + $r.name + " size=" + $r.size)
	}
	default { throw "unknown action: $Action" }
}
