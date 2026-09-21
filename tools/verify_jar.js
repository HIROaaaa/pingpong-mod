#!/usr/bin/env node
/**
 * 产物自检：读 build/libs/pingpong-<version>.jar，核对
 *   ① fabric.mod.json 里的 version 与期望一致；
 *   ② jar 里确实含本轮新增的符号（防止"改了源码但产物是旧的"这类假成功）；
 *   ③ 是否还残留被删掉的旧符号；
 *   ④ class 字节码 major version（52 = Java 8，M5 之后必须保持 52）。
 *
 * 【为什么要这个脚本】本机 gradle 在增量构建下会把 jar 大小压得很接近，
 * 光看"BUILD SUCCESSFUL + 文件存在"无法区分新旧产物（1.6.0 与 1.7.0 都是 95081 字节）。
 * 必须打开 jar 看内容。
 *
 * 用法：node tools/verify_jar.js 1.7.0 [新增符号1,新增符号2] [应消失符号1]
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const ROOT = path.resolve(__dirname, '..');

/**
 * 取 zip 中央目录里的某个条目并解压（不引第三方库）。
 *
 * 【踩坑】不能拿**本地文件头**里的「压缩后大小」来切片 —— jar 是流式写出来的，
 * 本地头里 method/size 常留 0，真实值要写进后面的 data descriptor，
 * 于是 inflateRawSync 会报 `unexpected end of file (Z_BUF_ERROR)`。
 * 中央目录里的 size 才是可信的，本地头只用来定位数据起点。
 */
function readZipEntry(file, name) {
  const b = fs.readFileSync(file);
  let i = b.length - 22;
  while (i >= 0 && b.readUInt32LE(i) !== 0x06054b50) i--;
  if (i < 0) throw new Error('不是合法 zip：' + file);
  const count = b.readUInt16LE(i + 10);
  let off = b.readUInt32LE(i + 16);
  for (let k = 0; k < count; k++) {
    const nl = b.readUInt16LE(off + 28);
    const el = b.readUInt16LE(off + 30);
    const cl = b.readUInt16LE(off + 32);
    const entryName = b.toString('utf8', off + 46, off + 46 + nl);
    const method = b.readUInt16LE(off + 10);        // ← 用中央目录的
    const compSize = b.readUInt32LE(off + 20);      // ← 用中央目录的
    const localOff = b.readUInt32LE(off + 42);
    if (entryName === name) {
      const lnl = b.readUInt16LE(localOff + 26);
      const lel = b.readUInt16LE(localOff + 28);
      const start = localOff + 30 + lnl + lel;
      const raw = b.subarray(start, start + compSize);
      return method === 0 ? raw : zlib.inflateRawSync(raw);
    }
    off += 46 + nl + el + cl;
  }
  return null;
}

const version = process.argv[2];
if (!version) {
  console.error('用法：node tools/verify_jar.js <version> [新增符号,..] [应消失符号,..]');
  process.exit(2);
}
const expectPresent = (process.argv[3] || '').split(',').filter(Boolean);
const expectAbsent = (process.argv[4] || '').split(',').filter(Boolean);

const jar = path.join(ROOT, 'build', 'libs', `pingpong-${version}.jar`);
if (!fs.existsSync(jar)) {
  console.error(`❌ 找不到产物 ${jar}`);
  process.exit(1);
}

let fails = 0;
const check = (label, ok, detail) => {
  if (!ok) fails++;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${label}${detail ? '   —— ' + detail : ''}`);
};

const bytes = fs.readFileSync(jar);
console.log(`=== 产物自检 ${path.basename(jar)}（${bytes.length} 字节）===`);

// ① fabric.mod.json 的 version
const fmj = readZipEntry(jar, 'fabric.mod.json');
if (!fmj) {
  check('fabric.mod.json 存在', false);
} else {
  const text = fmj.toString('utf8');
  const m = /"version"\s*:\s*"([^"]+)"/.exec(text);
  const got = m ? m[1] : '(未找到)';
  check('fabric.mod.json 版本号与产物名一致', got === version, `jar 名 v${version} / 内嵌 v${got}`);
}

// ② ③ 符号存在性（class 常量池里的方法名/字段名以字面串形式出现）
//
// 【语法】符号可以写成 `名字`（默认查 PingPongBallEntity）或 `名字@com/whale/pingpong/xxx/Class`，
// 后者用于查**独立的新类**——本工具第一版只查实体类，结果 PingPongModelPose 这种新类
// 明明在 jar 里却被报"不含新增符号"（假失败）。
const DEFAULT_CLASS = 'com/whale/pingpong/entity/PingPongBallEntity.class';
const classCache = new Map();
function classOf(spec) {
  const at = spec.indexOf('@');
  const clsPath = at < 0 ? DEFAULT_CLASS : spec.slice(at + 1) + '.class';
  if (!classCache.has(clsPath)) {
    classCache.set(clsPath, readZipEntry(jar, clsPath));
  }
  return { name: at < 0 ? spec : spec.slice(0, at), bytes: classCache.get(clsPath), clsPath };
}

const entityClass = readZipEntry(jar, DEFAULT_CLASS);

/**
 * 检查一个"期望存在"的符号。
 *
 * 【语法扩展】除了 class 符号（`名字` 或 `名字@com/…/Class`），
 * 还支持**资源文件**：写成 `assets/pingpong/models/item/xxx.json` 这样带路径的，
 * 就直接在 jar 条目里找它 —— 模型/贴图这类资源没法用"类符号"校验，
 * 但"有没有打进产物"同样必须验（用户遇到过改了模型却没生效的情况）。
 */
function checkPresent(spec) {
  // 【判断顺序】先看有没有 @（class 符号），再看有没有 /（资源路径）。
  // 反过来会把 `符号@com/…/Class` 误判成资源路径 —— 第一版就是这么错的。
  const isResource = !spec.includes('@') && spec.includes('/');
  if (isResource) {
    const found = readZipEntry(jar, spec) !== null;
    check(`产物含资源 ${spec}`, found);
    return;
  }
  const { name, bytes: cls, clsPath } = classOf(spec);
  if (!cls) {
    check(`含新增符号 ${name}`, false, `类不存在：${clsPath}`);
  } else {
    check(`含新增符号 ${name}`, cls.includes(Buffer.from(name)), clsPath);
  }
}

/** 检查一个"期望不存在"的符号（只支持 class 符号） */
function checkAbsent(spec) {
  const { name, bytes: cls, clsPath } = classOf(spec);
  if (!cls) {
    check(`已清除旧符号 ${name}`, false, `类不存在：${clsPath}`);
  } else {
    check(`已清除旧符号 ${name}`, !cls.includes(Buffer.from(name)), clsPath);
  }
}

for (const spec of expectPresent) {
  checkPresent(spec);
}
for (const spec of expectAbsent) {
  checkAbsent(spec);
}

// ④ 字节码版本（Java 8 = 52）
if (entityClass && entityClass.length > 8) {
  const major = entityClass.readUInt16BE(6);
  check('class 字节码 major version = 52（Java 8）', major === 52, `实测 ${major}`);
}

console.log(`\n===== ${fails === 0 ? '产物自检通过 ✅' : fails + ' 项异常 ❌'} =====`);
process.exit(fails === 0 ? 0 : 1);
