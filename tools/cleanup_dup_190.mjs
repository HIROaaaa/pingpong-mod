#!/usr/bin/env node
/**
 * 把 GitHub 上的 v1.9.0 收拾成唯一一个：保留**最后创建**的那个（它带刚上传的新 jar），
 * 删掉重复的（悬空或旧内容的），并清掉仍在的 v1.9.1 Release/tag。
 *
 * 【踩过的坑】上一版按“附件名含 pingpong-1.9.0.jar”挑保留对象 —— 但旧的 v1.9.0 Release
 * 里也有同名附件（旧内容），于是把新建的那个删了、留下旧的。正确判据是 **created_at 最新**。
 *
 * 用法：node tools/cleanup_dup_190.mjs [--dry]
 */
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PS1 = path.join(ROOT, 'tools', 'pp_github_190.ps1');
const DRY = process.argv.includes('--dry');

function gh(action, params = {}) {
  const args = ['-ExecutionPolicy', 'Bypass', '-File', PS1, '-Action', action];
  if (params.Tag) args.push('-Tag', String(params.Tag));
  if (params.ReleaseId) args.push('-ReleaseId', String(params.ReleaseId));
  return execFileSync('powershell', args, { cwd: ROOT, encoding: 'utf8' }).trim();
}

const script = `
$cred = "protocol=https\`nhost=github.com\`n\`n" | git credential fill 2>$null
$token = (($cred -split "\`n" | Where-Object { $_ -like 'password=*' }) -replace '^password=', '').Trim()
$H = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json'; 'User-Agent' = 'pp'; 'X-GitHub-Api-Version' = '2022-11-28' }
$rels = Invoke-RestMethod -Uri 'https://api.github.com/repos/HIROaaaa/pingpong-mod/releases?per_page=100' -Headers $H
foreach ($r in $rels) {
  $assets = (@($r.assets) | ForEach-Object { "$($_.id):$($_.name):$($_.size):$($_.created_at)" }) -join ';'
  Write-Output ("ID=$($r.id)|TAG=$($r.tag_name)|CREATED=$($r.created_at)|ASSETS=$assets")
}
`;
const entries = execFileSync('powershell', ['-NoProfile', '-Command', script], { cwd: ROOT, encoding: 'utf8' })
  .trim().split(/\r?\n/).filter(Boolean).map((line) => {
    const o = {};
    for (const kv of line.split('|')) { const i = kv.indexOf('='); o[kv.slice(0, i)] = kv.slice(i + 1); }
    return o;
  });

const log = [];
const v190 = entries.filter((e) => e.TAG === 'v1.9.0').sort((a, b) => (a.CREATED < b.CREATED ? 1 : -1));
const keep = v190[0];
log.push(`v1.9.0 release 数: ${v190.length}；保留最新 id=${keep ? keep.ID : 'none'} created=${keep ? keep.CREATED : '-'}`);
log.push(`  保留的那个附件: ${keep ? keep.ASSETS : '-'}`);

for (const e of v190.slice(1)) {
  if (DRY) { log.push(`[dry] 删重复 release id=${e.ID} created=${e.CREATED} assets=${e.ASSETS}`); continue; }
  gh('delete-release', { ReleaseId: Number(e.ID) });
  log.push(`已删重复 release id=${e.ID} created=${e.CREATED}`);
}

const v191 = entries.find((e) => e.TAG === 'v1.9.1');
if (v191) {
  if (DRY) { log.push(`[dry] 删 v1.9.1 release id=${v191.ID}`); }
  else {
    gh('delete-release', { ReleaseId: Number(v191.ID) });
    log.push(`已删 v1.9.1 release id=${v191.ID}`);
    const tagInfo = gh('tag-info', { Tag: 'v1.9.1' });
    if (tagInfo.includes('SHA=') && tagInfo.trim() !== 'SHA=') {
      gh('delete-tag', { Tag: 'v1.9.1' });
      log.push('已删 v1.9.1 tag');
    }
  }
} else {
  log.push('v1.9.1: 已无残留');
}

log.push('== after ==');
log.push(gh('list'));

writeFileSync(path.join(os.tmpdir(), 'pingpong-cleanup-190.log'), log.join('\n') + '\n', 'utf8');
console.log(log.join('\n'));
