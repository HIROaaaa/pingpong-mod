#!/usr/bin/env node
/**
 * mc-pingpong v1.9.0 版本收口：GitHub 侧操作（Node 编排 + PowerShell 发请求）。
 *
 * 【为什么这么分工】
 *   - Node 的 fetch(undici) 在本机因 TLS 中间证书报 UNABLE_TO_VERIFY_LEAF_SIGNATURE，
 *     curl 也报 exit 35 —— 只有 PowerShell 的 Invoke-RestMethod（走 Windows 证书存储）是通的；
 *   - 但 .ps1 在 PowerShell 5.1 下必须带 BOM 才能写中文，BOM 又会被编辑工具抹掉 → 用纯 ASCII 的 ps1，
 *     中文数据（Release 正文）由 Node 写成 UTF-8 的 JSON 文件，交给 ps1 用 File.ReadAllText 读。
 *
 * 做的事：
 *   1. 删 v1.9.1 ~ v1.9.11 的 Release 与 tag（逐个精确，硬编码清单）
 *   2. 旧 v1.9.0 tag（指 4ffc9a7）删除，重指到当前 HEAD
 *   3. 更新 v1.9.0 Release：正文取 CHANGELOG 的 1.9.0 小节，附件换成本地新 jar
 *
 * 用法：node tools/github_190.mjs [--dry]
 */
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, statSync } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PS1 = path.join(ROOT, 'tools', 'pp_github_190.ps1');
const KEEP = 'v1.9.0';
const VERSION = KEEP.replace(/^v/, '');
const DOOMED = ['v1.9.1', 'v1.9.2', 'v1.9.3', 'v1.9.4', 'v1.9.5', 'v1.9.6',
  'v1.9.7', 'v1.9.8', 'v1.9.9', 'v1.9.10', 'v1.9.11'];
const DRY = process.argv.includes('--dry');

/** 跑一个 ps1 动作，参数经环境变量传递（避免命令行引号/编码问题） */
function gh(action, params = {}) {
  for (const [k, v] of Object.entries(params)) process.env[`PPG_${k.toUpperCase()}`] = String(v);
  const args = ['-ExecutionPolicy', 'Bypass', '-File', PS1, '-Action', action];
  if (params.Tag) args.push('-Tag', String(params.Tag));
  if (params.Sha) args.push('-Sha', String(params.Sha));
  if (params.ReleaseId) args.push('-ReleaseId', String(params.ReleaseId));
  if (params.BodyFile) args.push('-BodyFile', String(params.BodyFile));
  if (params.Asset) args.push('-Asset', String(params.Asset));
  const out = execFileSync('powershell', args, { cwd: ROOT, encoding: 'utf8' });
  return out.trim();
}

/** 从 ps1 的 KEY=VALUE 输出里取一个字段 */
function field(out, key) {
  const hit = out.split(/\r?\n/).find((l) => l.startsWith(`${key}=`));
  return hit ? hit.slice(key.length + 1) : '';
}

function notesFor(version) {
  const lines = readFileSync(path.join(ROOT, 'CHANGELOG.md'), 'utf8').split(/\r?\n/);
  const esc = version.replace(/\./g, '\\.');
  const start = lines.findIndex((l) => new RegExp(`^##\\s*\\[?${esc}\\]?`).test(l));
  if (start < 0) throw new Error(`CHANGELOG 里没有 ${version} 的小节`);
  const rest = lines.slice(start);
  const end = rest.findIndex((l, i) => i > 0 && /^##\s/.test(l));
  return (end > 0 ? rest.slice(0, end - 1) : rest).join('\n').trim();
}

const jar = path.join(ROOT, 'build', 'libs', `pingpong-${VERSION}.jar`);
const log = [];

// ---- 0. 现状 ----
log.push('== before ==');
log.push(gh('list'));

if (DRY) {
  for (const t of DOOMED) log.push(`[dry] would delete tag+release: ${t}`);
  log.push(`[dry] would re-point ${KEEP} to HEAD and refresh its release`);
} else {
  // ---- 1. 删中间版本 ----
  for (const tag of DOOMED) {
    if (!/^v1\.9\.\d+$/.test(tag)) throw new Error(`bad tag in list: ${tag}`);
    const info = gh('release-info', { Tag: tag });
    const id = field(info, 'ID');
    if (id) {
      gh('delete-release', { ReleaseId: Number(id) });
      log.push(`deleted release ${tag} (id=${id})`);
    } else {
      log.push(`skip release ${tag} (absent)`);
    }
    const tagInfo = gh('tag-info', { Tag: tag });
    if (field(tagInfo, 'SHA')) {
      gh('delete-tag', { Tag: tag });
      log.push(`deleted tag ${tag}`);
    } else {
      log.push(`skip tag ${tag} (absent)`);
    }
  }

  // ---- 2. 重建 v1.9.0 tag ----
  const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim();
  const oldSha = field(gh('tag-info', { Tag: KEEP }), 'SHA');
  if (oldSha) {
    gh('delete-tag', { Tag: KEEP });
    log.push(`deleted old ${KEEP} tag (was ${oldSha.slice(0, 7)})`);
  }
  gh('create-tag', { Tag: KEEP, Sha: head });
  log.push(`created ${KEEP} -> ${head.slice(0, 7)}`);

  // ---- 3. 刷新 Release（不存在就建，幂等）----
  let relInfo = gh('release-info', { Tag: KEEP });
  let relId = Number(field(relInfo, 'ID'));
  const notes = notesFor(VERSION);
  const payloadFile = path.join(os.tmpdir(), 'pingpong-190-release.json');
  const basePayload = {
    name: `${KEEP} — 动作系统与球拍建模合并版`,
    body: notes,
    draft: false,
    prerelease: false,
  };
  if (!relId) {
    writeFileSync(payloadFile, JSON.stringify({ ...basePayload, tag_name: KEEP, target_commitish: 'main' }), 'utf8');
    log.push(gh('create-release', { BodyFile: payloadFile }));
    relInfo = gh('release-info', { Tag: KEEP });
    relId = Number(field(relInfo, 'ID'));
    if (!relId) throw new Error(`${KEEP} release create failed`);
  } else {
    writeFileSync(payloadFile, JSON.stringify(basePayload), 'utf8');
    log.push(gh('patch-release', { ReleaseId: relId, BodyFile: payloadFile }));
  }
  const assets = field(relInfo, 'ASSETS');
  log.push(`release body: ${notes.length} chars`);
  for (const item of assets ? assets.split(',') : []) {
    const [aid, aname] = item.split(':');
    gh('delete-asset', { ReleaseId: Number(aid) });
    log.push(`deleted asset ${aname}`);
  }
  log.push(gh('upload-asset', { ReleaseId: relId, Asset: jar }));
  log.push(`jar: ${(statSync(jar).size / 1024).toFixed(0)}KB`);

  // ---- 4. 复查 ----
  log.push('== after ==');
  log.push(gh('list'));
}

mkdirSync(path.join(ROOT, 'tools'), { recursive: true });
writeFileSync(path.join(ROOT, 'tools', '.github_190.log'), log.join('\n') + '\n', 'utf8');
console.log(log.join('\n'));
