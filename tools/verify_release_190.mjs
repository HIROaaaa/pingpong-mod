#!/usr/bin/env node
/**
 * 校验 GitHub 上 v1.9.0 Release 的最终状态（正文中文是否完好、附件是否是刚构建的那个 jar）。
 *
 * 为什么正文要走 base64：ps1 在 PowerShell 5.1 下把中文写进控制台会按 GBK 编码，
 * Node 读回来就是乱码。让 ps1 自己把 JSON 转 base64，Node 解码——编码链上两端都确定。
 *
 * 用法：node tools/verify_release_190.mjs
 */
import { execFileSync } from 'node:child_process';
import { readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PS1 = path.join(ROOT, 'tools', 'pp_github_190.ps1');
const jar = path.join(ROOT, 'build', 'libs', 'pingpong-1.9.0.jar');
const localJarBytes = statSync(jar).size;

const out = execFileSync('powershell', ['-ExecutionPolicy', 'Bypass', '-File', PS1, '-Action', 'release-info', '-Tag', 'v1.9.0'],
  { cwd: ROOT, encoding: 'utf8' });
const b64 = (out.split(/\r?\n/).find((l) => l.startsWith('B64=')) || '').slice(4);
const info = JSON.parse(Buffer.from(b64, 'base64').toString('utf8'));

const checks = [];
const check = (ok, label, detail) => checks.push({ ok, label, detail });

check(info.tag_name === 'v1.9.0', 'tag 是 v1.9.0', info.tag_name);
check(/动作系统与球拍建模合并版/.test(info.name || ''), '标题中文完好', info.name);
check(/动作系统（M6）与球拍建模（M9）合并版/.test(info.body || ''), '正文含合并说明', (info.body || '').slice(0, 24));
check(!/锟|鏇|鐞|鈥/.test(info.body || ''), '正文无 GBK 乱码特征', '');
// PowerShell 的 ConvertTo-Json 在只有一个元素时会退化成对象而不是数组
const assets = Array.isArray(info.assets) ? info.assets : info.assets ? [info.assets] : [];
const asset = assets.find((a) => a.name === 'pingpong-1.9.0.jar');
check(!!asset, '附件 pingpong-1.9.0.jar 存在', asset ? `${asset.size} bytes` : 'missing');
check(asset && asset.size === localJarBytes, '附件尺寸与本地构建一致', `${asset ? asset.size : '-'} vs ${localJarBytes}`);

console.log(`Release: ${info.tag_name} | id=${info.id} | created=${info.created_at}`);
console.log(`name: ${info.name}`);
console.log(`body: ${info.body.length} chars, 前两行:`);
for (const line of info.body.split('\n').slice(0, 2)) console.log(`  ${line}`);
console.log('checks:');
for (const c of checks) console.log(`  ${c.ok ? 'ok  ' : 'FAIL'} ${c.label}${c.detail ? '  -- ' + c.detail : ''}`);
const bad = checks.filter((c) => !c.ok);
console.log(bad.length ? `\n${bad.length} 项异常` : '\nVERIFY OK');
process.exit(bad.length ? 1 : 0);
