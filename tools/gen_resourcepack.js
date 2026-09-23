#!/usr/bin/env node
/**
 * 生成「只覆盖球拍材质」的独立材质包（用户 2026-09-23 要求）。
 *
 * 【为什么要单独做材质包】mod 内的贴图改了几轮、用户仍说"还是不行"，
 * 那就需要一条**能独立验证的通道**：材质包插在 mod 资源之上（资源包优先级高于 mod），
 * 如果材质包里的球拍变了 → 说明资源加载链路没问题、问题在 mod 内贴图内容；
 * 如果材质包也不变 → 说明根本没加载到这张贴图（缓存/路径/版本问题）。
 *
 * 关键约束：**贴图必须与模型 UV 严格对应**（模型是的 uv 按 32×32 四象限写的）：
 *   左上 16×16 = 正面红胶皮   uv [0,0,8,8]
 *   右上 16×16 = 反面黑胶皮   uv [8,0,16,8]
 *   左下 16×16 = 木芯(柄)     uv [0,8,8,16]
 *   右下 16×16 = 木芯(侧面)   uv [8,8,16,16]
 * 这一版把颜色画得更"断言式"：胶皮用饱和红/纯黑 + 明显颗粒，木纹用深浅条纹，
 * 一眼就能分清哪块是哪块。
 *
 * 用法：node tools/gen_resourcepack.js
 * 产物：dist/pingpong-paddle-pack.zip（直接丢进 .minecraft/resourcepacks/）
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.resolve(__dirname, '..');
const OUT_DIR = path.join(ROOT, 'dist');
const PACK_NAME = 'pingpong-paddle-pack';

// ---------- 极简 PNG 编码（RGBA8，无第三方依赖）----------
function crcTable() {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
}
const CRC = crcTable();
function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}
function encodePng(width, height, rgba) {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const raw = Buffer.alloc(height * (width * 4 + 1));
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    rgba.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  return Buffer.concat([
    sig,
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}
function makeCanvas(size) {
  const data = Buffer.alloc(size * size * 4, 0);
  return {
    size,
    data,
    set(x, y, [r, g, b, a = 255]) {
      if (x < 0 || y < 0 || x >= size || y >= size) return;
      const i = (y * size + x) * 4;
      data[i] = r; data[i + 1] = g; data[i + 2] = b; data[i + 3] = a;
    },
    rect(x0, y0, x1, y1, c) {
      for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) this.set(x, y, c);
    },
  };
}

// ---------- 画四象限 ----------
const SIZE = 32;

/** 正面红胶皮：饱和红 + 规则颗粒 + 四边暗化（像真实胶皮的颗粒面） */
function redRubber(c, ox, oy, S) {
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const edge = x === 0 || y === 0 || x === 15 || y === 15;
      const grain = (x % 4 === 1 && y % 4 === 1) || (x % 4 === 3 && y % 4 === 2);
      let col = edge ? [150, 20, 20] : grain ? [235, 60, 45] : [205, 30, 30];
      c.set(ox + x, oy + y, [...col, 255]);
    }
  }
}

/** 反面黑胶皮：纯黑 + 颗粒高光 */
function blackRubber(c, ox, oy) {
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const edge = x === 0 || y === 0 || x === 15 || y === 15;
      const grain = (x % 4 === 1 && y % 4 === 1) || (x % 4 === 3 && y % 4 === 2);
      const col = edge ? [18, 18, 20] : grain ? [58, 58, 64] : [34, 34, 38];
      c.set(ox + x, oy + y, [...col, 255]);
    }
  }
}

/** 木芯（柄）：浅木色 + 竖向纹理 */
function woodGrain(c, ox, oy, light) {
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const stripe = x % 3 === 0;
      const col = light
        ? (stripe ? [163, 116, 62] : [196, 148, 88])
        : (stripe ? [110, 72, 36] : [140, 96, 48]);
      c.set(ox + x, oy + y, [...col, 255]);
    }
  }
}

function paddleTexture() {
  const c = makeCanvas(SIZE);
  redRubber(c, 0, 0);        // 左上：正面红胶皮  uv [0,0,8,8]
  blackRubber(c, 16, 0);     // 右上：反面黑胶皮  uv [8,0,16,8]
  woodGrain(c, 0, 16, true); // 左下：木芯(柄)    uv [0,8,8,16]
  woodGrain(c, 16, 16, false); // 右下：木芯侧面  uv [8,8,16,16]
  return c;
}

// ---------- 极简 ZIP 打包（store 模式：不压缩，MC 也认）----------
function zipStore(entries) {
  const parts = [];
  const central = [];
  let offset = 0;
  for (const [name, buf] of entries) {
    const nameBuf = Buffer.from(name, 'utf8');
    const crc = crc32(buf);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);       // version needed
    local.writeUInt16LE(0x0800, 6);   // UTF-8 flag
    local.writeUInt16LE(0, 8);        // store
    local.writeUInt16LE(0, 10); local.writeUInt16LE(0, 12); // time/date
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(buf.length, 18);
    local.writeUInt32LE(buf.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);
    parts.push(local, nameBuf, buf);

    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0);
    cd.writeUInt16LE(20, 4); cd.writeUInt16LE(20, 6);
    cd.writeUInt16LE(0x0800, 8);
    cd.writeUInt16LE(0, 10);
    cd.writeUInt16LE(0, 12); cd.writeUInt16LE(0, 14);
    cd.writeUInt32LE(crc, 16);
    cd.writeUInt32LE(buf.length, 20);
    cd.writeUInt32LE(buf.length, 24);
    cd.writeUInt16LE(nameBuf.length, 28);
    cd.writeUInt16LE(0, 30); cd.writeUInt16LE(0, 32);
    cd.writeUInt16LE(0, 34); cd.writeUInt16LE(0, 36);
    cd.writeUInt32LE(0, 38);
    cd.writeUInt32LE(offset, 42);
    central.push(Buffer.concat([cd, nameBuf]));

    offset += local.length + nameBuf.length + buf.length;
  }
  const cdBuf = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4); eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cdBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);
  return Buffer.concat([...parts, cdBuf, eocd]);
}

// ---------- 组装 ----------
const packMeta = {
  pack: {
    pack_format: 15,   // 1.20.1
    description: 'PingPong 球拍材质覆盖包（正红胶皮 / 黑胶皮 / 木柄）— 只改球拍贴图',
  },
};

const texPath = 'assets/pingpong/textures/item/pingpong_paddle.png';
const entries = [
  ['pack.mcmeta', Buffer.from(JSON.stringify(packMeta, null, 2), 'utf8')],
  [texPath, encodePng(SIZE, SIZE, paddleTexture().data)],
  // 顺手把模型入口也带上（与资源包内贴图配套；行为与原版一致，只是保证路径存在）
  ['pack.png', encodePng(SIZE, SIZE, paddleTexture().data)],
];

fs.mkdirSync(OUT_DIR, { recursive: true });
const zipPath = path.join(OUT_DIR, `${PACK_NAME}.zip`);
fs.writeFileSync(zipPath, zipStore(entries));

// 同时落一份未打包的目录，方便用户改
const looseDir = path.join(OUT_DIR, PACK_NAME);
fs.rmSync(looseDir, { recursive: true, force: true });
for (const [name, buf] of entries) {
  const p = path.join(looseDir, name);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, buf);
}

console.log(`已生成材质包：${zipPath}`);
console.log(`  pack_format = ${packMeta.pack.pack_format} (1.20.1)`);
console.log('  内容：');
for (const [name, buf] of entries) console.log(`    ${name}  ${buf.length} 字节`);
console.log(`同时输出可编辑目录：${looseDir}`);
console.log('\n安装：把 zip 丢进 .minecraft/resourcepacks/ 并在游戏里启用（放在最上层）。');
