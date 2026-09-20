#!/usr/bin/env node
/**
 * 推送前门禁：扫「即将提交的内容」里有没有不该进仓库的东西。
 *
 * 【为什么需要】用户明确要求：推到 GitHub 之前先检查有没有会影响本机安全的文件，
 * 并且「如果之前也提交过这种文件，就把这些文件都删掉」。第一遍人工排查的结论是仓库干净，
 * 但这个检查必须是**每次提交都自动跑**的，否则下次手滑就漏了。
 *
 * 检查对象：`git diff --cached --name-only`（已 staged 的文件）+ 这些文件的真实内容。
 * 命中任意一条 → 退出码 1（阻止提交），并打印原因与处置建议。
 *
 * 两类检查：
 *   A. 文件名：凭据/密钥/本地环境文件（.env、*.jks、*.pem、*.key、*.pfx、local.properties…）
 *   B. 文件内容：私钥块、GitHub/npm/OpenAI/DeepSeek 常见 token 形态、绝对路径泄漏（C:\Users\<用户名>）
 *
 * 例外面：已知的「本来就该有的」命中点写在 ALLOWLIST 里（例如发布脚本里出现 "token" 这个词），
 * 只对**当前仓库实际存在**的这几处放行，避免误杀。
 *
 * 用法：
 *   node tools/pre_push_check.js           # 检查暂存区（pre-commit 钩子调用）
 *   node tools/pre_push_check.js --all     # 检查全部已跟踪文件（体检用）
 */
'use strict';

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const REPO = path.resolve(__dirname, '..');
const CHECK_ALL = process.argv.includes('--all');

// ------------------------------------------------------------------
// A. 文件名黑名单（正则，针对仓库相对路径，小写后匹配）
// ------------------------------------------------------------------
const BAD_NAME_PATTERNS = [
  [/\.env($|\.)/, '环境变量文件（常含密钥）'],
  [/\.(jks|keystore|p12|pfx)$/, '证书/密钥库'],
  [/\.(pem|key|ppk)$/, '私钥文件'],
  [/(^|\/)local\.properties$/, '本地环境配置（常含绝对路径与 SDK 位置）'],
  [/(^|\/)id_(rsa|ed25519|ecdsa)(\.pub)?$/, 'SSH 私钥'],
  [/(credential|secret|password|token)s?\.(json|ya?ml|txt|ini)$/, '疑似凭据文件'],
  [/\.(tgz|vsix|zip|jar)$/, '二进制包（jar 只允许 gradle-wrapper.jar）'],
];

/** 允许存在的二进制例外 */
const ALLOWED_BINARIES = new Set(['gradle/wrapper/gradle-wrapper.jar']);

// ------------------------------------------------------------------
// B. 内容黑名单（正则 + 说明）
// ------------------------------------------------------------------
const BAD_CONTENT_PATTERNS = [
  [/-----BEGIN [A-Z ]*PRIVATE KEY-----/, '私钥内容'],
  [/\bghp_[A-Za-z0-9]{20,}/, 'GitHub personal access token'],
  [/\bgithub_pat_[A-Za-z0-9_]{20,}/, 'GitHub fine-grained token'],
  [/\bsk-[A-Za-z0-9]{24,}/, '疑似 OpenAI/DeepSeek 风格的 API key'],
  [/\bnpm_[A-Za-z0-9]{30,}/, 'npm token'],
  [/\bAKIA[0-9A-Z]{16}\b/, 'AWS access key id'],
  [/C:\\\\Users\\\\[^\\\\\r\n"']+/, 'Windows 绝对路径（泄漏本机用户名）'],
  [/\/home\/[a-z0-9_-]+\//i, 'Linux 绝对路径（泄漏本机用户名）'],
];

/** 文件级例外：这些文件里允许出现某些"看起来像密钥"的词（只是文档说明/变量名） */
const CONTENT_ALLOW = {
  'tools/pre_push_check.js': ['token', 'password', 'secret', 'private key'],
  'tools/publish_release.ps1': ['token', 'password', 'Authorization'],
  'README.md': ['token', 'password'],
  'docs/plan/v1.3-plan.md': ['token', 'absolute'],
};

/** 文本文件判定：这些扩展名才做内容扫描（避免扫贴图/二进制浪费时间） */
const TEXT_EXT = new Set([
  '.java', '.json', '.json5', '.txt', '.md', '.yml', '.yaml', '.gradle', '.properties',
  '.js', '.mjs', '.cjs', '.ts', '.ps1', '.bat', '.cmd', '.sh', '.xml', '.toml', '.gitignore', '.gitattributes',
]);

function git(args) {
  return execFileSync('git', args, { cwd: REPO, encoding: 'utf8' });
}

function listFiles() {
  const out = CHECK_ALL
    ? git(['ls-files'])
    : git(['diff', '--cached', '--name-only', '--diff-filter=ACMR']);
  return out.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
}

function readText(rel) {
  const abs = path.join(REPO, rel);
  if (!fs.existsSync(abs)) return null;
  const ext = path.extname(rel).toLowerCase();
  const base = path.basename(rel).toLowerCase();
  const isText = TEXT_EXT.has(ext) || base === 'gitignore' || base === 'gradlew' || rel.endsWith('.log');
  if (!isText) return null;
  const size = fs.statSync(abs).size;
  if (size > 2 * 1024 * 1024) return null; // 超过 2MB 的文本不扫
  return fs.readFileSync(abs, 'utf8');
}

const problems = [];
const warnings = [];

const files = listFiles();
if (files.length === 0) {
  console.log(CHECK_ALL ? '没有已跟踪文件。' : '暂存区为空，无需检查。');
  process.exit(0);
}

for (const rel of files) {
  const lower = rel.toLowerCase().replace(/\\/g, '/');

  // --- A. 文件名 ---
  for (const [re, why] of BAD_NAME_PATTERNS) {
    if (!re.test(lower)) continue;
    if (ALLOWED_BINARIES.has(lower)) continue;
    problems.push(`[文件名] ${rel} —— ${why}`);
    break;
  }

  // --- B. 内容 ---
  const text = readText(rel);
  if (text === null) continue;
  const allow = CONTENT_ALLOW[rel] || [];
  for (const [re, why] of BAD_CONTENT_PATTERNS) {
    const m = text.match(re);
    if (!m) continue;
    const hit = m[0];
    // 例外面：允许该文件出现某些词（例如脚本里必须写 "token = ..."），
    // 但只要命中的是**真正的密钥形态**（长随机串），任何人都不能被放行。
    const looksLikeRealSecret = /\b(ghp_|github_pat_|sk-|npm_|AKIA)/.test(hit) || /PRIVATE KEY/.test(hit);
    if (!looksLikeRealSecret && allow.some((w) => hit.toLowerCase().includes(w))) continue;
    problems.push(`[内容] ${rel} —— ${why}：${JSON.stringify(hit.slice(0, 60))}`);
  }

  // --- C. 大文件提醒（不算失败） ---
  const abs = path.join(REPO, rel);
  if (fs.existsSync(abs)) {
    const kb = fs.statSync(abs).size / 1024;
    if (kb > 2048) warnings.push(`[大文件] ${rel} —— ${Math.round(kb)}KB，确认是否真要进仓库`);
  }
}

if (warnings.length) {
  console.log('提醒（不阻止提交）：');
  for (const w of warnings) console.log('  ' + w);
  console.log('');
}

if (problems.length) {
  console.error('❌ 推送前门禁拦下了这些问题：\n');
  for (const p of problems) console.error('  ' + p);
  console.error('\n处置建议：');
  console.error('  1. 凭据/密钥类：立刻从暂存区移除（git restore --staged <file>），并把该文件加进 .gitignore；');
  console.error('  2. 绝对路径类：改成相对路径，或用 <user> / <path> 占位；');
  console.error('  3. 如果这些内容**已经在历史提交里**：需要重写历史（git filter-repo）并轮换密钥，先找我确认。');
  process.exit(1);
}

console.log(`✅ 门禁通过：检查了 ${files.length} 个文件，没有发现凭据、私钥或本机绝对路径。`);
process.exit(0);
