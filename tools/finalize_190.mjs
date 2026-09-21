#!/usr/bin/env node
/**
 * 把 mc-pingpong 的版本线收口到单个 v1.9.0（GitHub 侧操作，用户 2026-09-21 拍板）。
 *
 * 【为什么是 Node + curl，而不是纯 PowerShell 脚本】
 *   1. .ps1 在 PowerShell 5.1 下必须有 BOM，而 BOM 会被编辑器/工具链反复抹掉 → 中文直接语法炸；
 *      PowerShell 的 `2>&1` 还会把 git 写到 stderr 的正常进度当 NativeCommandError 掐断脚本。
 *   2. Node 自带的 fetch（undici）在本机失败于 `UNABLE_TO_VERIFY_LEAF_SIGNATURE`
 *      —— 本机跑着 HTTPS 加速/代理，中间证书不在 Node 的 CA 链里；
 *      而 curl 走 Windows 证书存储，同一台机器上 api.github.com 是通的 → 用 curl 发请求。
 *
 * 做的事：
 *   1. 逐个精确删除 v1.9.1 ~ v1.9.11 的 Release 与 tag（只删清单内的，硬编码）
 *   2. 删旧的 v1.9.0 tag（原指 4ffc9a7，是 1.9.0 刚开线时的提交，不是同一棵树）
 *   3. 把 v1.9.0 重指到当前 HEAD
 *   4. 更新 v1.9.0 Release 的正文（取 CHANGELOG 的 1.9.0 小节）与附件（换成本地新 jar）
 *
 * 用法：node tools/finalize_190.mjs [--dry]
 */
import { execSync, spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO = 'HIROaaaa/pingpong-mod';
const KEEP = 'v1.9.0';
const VERSION = KEEP.replace(/^v/, '');
const DOOMED = ['v1.9.1', 'v1.9.2', 'v1.9.3', 'v1.9.4', 'v1.9.5', 'v1.9.6',
  'v1.9.7', 'v1.9.8', 'v1.9.9', 'v1.9.10', 'v1.9.11'];
const DRY = process.argv.includes('--dry');

// ---- 凭据（git 凭据管理器，不落盘、不打印）----
const raw = execSync('git credential fill', {
  input: 'protocol=https\nhost=github.com\n\n',
  cwd: ROOT,
  encoding: 'utf8',
});
const token = (raw.split(/\r?\n/).find((l) => l.startsWith('password=')) || '').slice(9).trim();
if (!token) throw new Error('git 凭据管理器里没有 github.com 的 token');

function curl(args, input) {
  const r = spawnSync('curl.exe', args, {
    cwd: ROOT,
    encoding: 'utf8',
    input,
    maxBuffer: 64 * 1024 * 1024,
  });
  if (r.status !== 0) throw new Error(`curl 失败(${r.status}): ${(r.stderr || '').slice(0, 300)}`);
  return (r.stdout || '').trim();
}

const BASE = `https://api.github.com/repos/${REPO}`;
function apiRaw(method, url, bodyJson, bodyFile, contentType) {
  const args = ['-s', '-X', method, '-H', `Authorization: Bearer ${token}`,
    '-H', 'Accept: application/vnd.github+json', '-H', 'User-Agent: pingpong-finalize',
    '-H', 'X-GitHub-Api-Version: 2022-11-28', '-w', '\\n%{http_code}'];
  let input;
  if (bodyJson !== undefined) {
    args.push('-H', 'Content-Type: application/json', '--data-binary', '@-');
    input = JSON.stringify(bodyJson);
  } else if (bodyFile) {
    args.push('-H', `Content-Type: ${contentType}`, '--data-binary', `@${bodyFile}`);
  }
  args.push(url);
  const out = curl(args, input);
  const nl = out.lastIndexOf('\n');
  const code = Number(out.slice(nl + 1));
  const text = out.slice(0, nl).trim();
  if (code === 404) return { code, data: null };
  if (code < 200 || code >= 300) throw new Error(`${method} ${url} -> ${code} ${text.slice(0, 300)}`);
  return { code, data: text ? JSON.parse(text) : null };
}
const api = (m, u, b) => apiRaw(m, u, b).data;

// ---- CHANGELOG 里对应版本的小节 ----
function notesFor(version) {
  const lines = readFileSync(path.join(ROOT, 'CHANGELOG.md'), 'utf8').split(/\r?\n/);
  const esc = version.replace(/\./g, '\\.');
  const start = lines.findIndex((l) => new RegExp(`^##\\s*\\[?${esc}\\]?`).test(l));
  if (start < 0) return `版本 ${version}`;
  const rest = lines.slice(start);
  const end = rest.findIndex((l, i) => i > 0 && /^##\s/.test(l));
  return (end > 0 ? rest.slice(0, end - 1) : rest).join('\n').trim();
}

const log = [];

// ---- 1. 删中间版本的 Release + tag ----
for (const tag of DOOMED) {
  if (!/^v1\.9\.\d+$/.test(tag)) throw new Error(`清单里有非法 tag：${tag}`);
  const rel = api('GET', `${BASE}/releases/tags/${tag}`);
  const ref = api('GET', `${BASE}/git/ref/tags/${tag}`);
  if (DRY) {
    log.push(`[dry] ${tag}: release=${rel ? rel.id : '-'} ref=${ref ? ref.object.sha.slice(0, 7) : '-'}`);
    continue;
  }
  if (rel) { api('DELETE', `${BASE}/releases/${rel.id}`); log.push(`删 Release ${tag}`); }
  if (ref) { api('DELETE', `${BASE}/git/refs/tags/${tag}`); log.push(`删 tag ${tag}`); }
  if (!rel && !ref) log.push(`跳过 ${tag}（远端本就没有）`);
}

// ---- 2+3. 重建 v1.9.0 tag 指向当前 HEAD ----
const head = execSync('git rev-parse HEAD', { cwd: ROOT, encoding: 'utf8' }).trim();
if (DRY) {
  log.push(`[dry] ${KEEP} 将由远端旧 tag 重指到 ${head.slice(0, 7)}`);
} else {
  const old = api('GET', `${BASE}/git/ref/tags/${KEEP}`);
  if (old) {
    api('DELETE', `${BASE}/git/refs/tags/${KEEP}`);
    log.push(`删旧 tag ${KEEP}（原 ${old.object.sha.slice(0, 7)}）`);
  }
  api('POST', `${BASE}/git/refs`, { ref: `refs/tags/${KEEP}`, sha: head });
  log.push(`建 tag ${KEEP} -> ${head.slice(0, 7)}`);
}

// ---- 4. 更新 Release 正文与附件 ----
const jar = path.join(ROOT, 'build', 'libs', `pingpong-${VERSION}.jar`);
if (!DRY) {
  const rel = api('GET', `${BASE}/releases/tags/${KEEP}`);
  const notes = notesFor(VERSION);
  if (!rel) throw new Error(`${KEEP} 的 Release 不存在，无法更新`);
  api('PATCH', `${BASE}/releases/${rel.id}`, {
    name: `${KEEP} — 动作系统与球拍建模合并版`,
    body: notes,
    draft: false,
    prerelease: false,
  });
  log.push(`更新 Release 正文（${notes.length} 字符）`);
  for (const a of rel.assets || []) {
    api('DELETE', `${BASE}/releases/assets/${a.id}`);
    log.push(`  删旧附件 ${a.name}`);
  }
  apiRaw('POST', `https://uploads.github.com/repos/${REPO}/releases/${rel.id}/assets?name=${encodeURIComponent(path.basename(jar))}`,
    undefined, jar, 'application/java-archive');
  log.push(`  上传附件 ${path.basename(jar)} (${(statSync(jar).size / 1024).toFixed(0)}KB)`);
}

// ---- 5. 复查 ----
if (!DRY) {
  const rels = api('GET', `${BASE}/releases?per_page=100`);
  const refs = api('GET', `${BASE}/git/refs/tags`);
  log.push(`远端 Release：${rels.map((r) => r.tag_name).join(', ')}`);
  log.push(`远端 tag：${[...new Set(refs.map((r) => r.ref.replace('refs/tags/', '')))].sort().join(', ')}`);
}

writeFileSync(path.join(ROOT, 'tools', '.finalize_190.log'), log.join('\n') + '\n', 'utf8');
console.log(log.join('\n'));
