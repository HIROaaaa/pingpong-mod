#!/usr/bin/env node
/**
 * 在开发实例（run/）里启用材质包，供 runClient 截图验证使用。
 *
 * 【为什么要脚本】Minecraft 的 options.txt 里资源包是 `resourcePacks:["fabric","file/xxx.zip"]`，
 * 用 PowerShell 的 node -e 单行写这段带引号的内容会被 shell 吃掉引号（本项目已踩多次），
 * 所以固化成脚本：读 options.txt → 替换/追加 resourcePacks 行 → 写回（UTF-8，不引 BOM）。
 *
 * 用法：node tools/enable_dev_resourcepack.js <包名.zip>
 *   例：node tools/enable_dev_resourcepack.js pingpong-paddle-pack.zip
 *   带 - 前缀的参数可以移除：node tools/enable_dev_resourcepack.js -pingpong-paddle-pack.zip
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const OPTIONS = path.join(ROOT, 'run', 'options.txt');
const arg = process.argv[2];
if (!arg) {
  console.error('用法：node tools/enable_dev_resourcepack.js <包名.zip>（前缀 - 表示移除）');
  process.exit(2);
}

const remove = arg.startsWith('-');
const packName = remove ? arg.slice(1) : arg;
const entry = `file/${packName}`;

let text = fs.readFileSync(OPTIONS, 'utf8');
const lineRe = /^resourcePacks:(.*)$/m;
const m = lineRe.exec(text);

// 解析已有的资源包列表（形如 ["fabric","file/xxx.zip"]）
let list = [];
if (m) {
  const inner = m[1].trim().replace(/^\[|\]$/g, '');
  list = inner
    .split(',')
    .map((s) => s.trim().replace(/^"|"$/g, ''))
    .filter(Boolean);
}
const before = [...list];

if (remove) {
  list = list.filter((p) => p !== entry);
} else if (!list.includes(entry)) {
  list.push(entry);
}

const newLine = `resourcePacks:[${list.map((p) => `"${p}"`).join(',')}]`;
text = m ? text.replace(lineRe, newLine) : `${text}\n${newLine}\n`;
if (!text.endsWith('\n')) text += '\n';
fs.writeFileSync(OPTIONS, text, 'utf8');

console.log(`改前: [${before.join(', ')}]`);
console.log(`改后: [${list.join(', ')}]`);
console.log(remove ? `已移除 ${entry}` : `已启用 ${entry}`);
console.log(`提示：材质包文件需放在 ${path.join(ROOT, 'run', 'resourcepacks')}`);
