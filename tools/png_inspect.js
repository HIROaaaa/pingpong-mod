#!/usr/bin/env node
/**
 * 无依赖 PNG 像素检查器：量出贴图各区域的实际颜色与边界。
 *
 * 用途（mc-pingpong 球拍材质排查）：模型 UV 是按 32×32 布局写的，而贴图是 64×64，
 * 导致每个面采样到两倍大的区域、拍面显示成木色。这个脚本把贴图真值读出来，
 * 用于确定"UV 该写多少"或"贴图该重画成多大"。
 *
 * 用法：node tools/png_inspect.js <png 路径>
 */
import { readFileSync } from 'node:fs';
import { inflateSync } from 'node:zlib';

const file = process.argv[2];
if (!file) { console.error('用法：node tools/png_inspect.js <png>'); process.exit(2); }
const buf = readFileSync(file);

// --- 解析 PNG 块 ---
let pos = 8;               // 跳过签名
let width = 0, height = 0, bitDepth = 0, colorType = 0;
const idat = [];
while (pos < buf.length) {
  const len = buf.readUInt32BE(pos);
  const type = buf.toString('ascii', pos + 4, pos + 8);
  const data = buf.subarray(pos + 8, pos + 8 + len);
  if (type === 'IHDR') {
    width = data.readUInt32BE(0);
    height = data.readUInt32BE(4);
    bitDepth = data[8];
    colorType = data[9];
    console.log(`尺寸 ${width}×${height}, 位深 ${bitDepth}, 颜色类型 ${colorType}` +
      ` (${colorType === 6 ? 'RGBA' : colorType === 2 ? 'RGB' : colorType === 3 ? '索引' : '其他'})`);
  } else if (type === 'IDAT') {
    idat.push(data);
  } else if (type === 'IEND') {
    break;
  }
  pos += 12 + len;
}

const channels = colorType === 6 ? 4 : colorType === 2 ? 3 : colorType === 3 ? 1 : 0;
if (channels === 0 || bitDepth !== 8) {
  console.error(`暂不支持：位深 ${bitDepth} / 颜色类型 ${colorType}`);
  process.exit(1);
}

// --- 解压 + 反过滤 ---
const raw = inflateSync(Buffer.concat(idat));
const stride = width * channels;
const px = Buffer.alloc(height * stride);
let rp = 0;
for (let y = 0; y < height; y++) {
  const filter = raw[rp++];
  const line = raw.subarray(rp, rp + stride);
  rp += stride;
  const cur = px.subarray(y * stride, (y + 1) * stride);
  const prev = y > 0 ? px.subarray((y - 1) * stride, y * stride) : Buffer.alloc(stride);
  for (let x = 0; x < stride; x++) {
    const a = x >= channels ? cur[x - channels] : 0;
    const b = prev[x];
    const c = x >= channels ? prev[x - channels] : 0;
    let v = line[x];
    if (filter === 1) v = (v + a) & 0xff;
    else if (filter === 2) v = (v + b) & 0xff;
    else if (filter === 3) v = (v + ((a + b) >> 1)) & 0xff;
    else if (filter === 4) {
      const p = a + b - c;
      const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
      v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 0xff;
    }
    cur[x] = v;
  }
}

const at = (x, y) => {
  const o = y * stride + x * channels;
  return [px[o], px[o + 1], px[o + 2]];
};
const hex = (c) => '#' + c.map((v) => v.toString(16).padStart(2, '0')).join('');

// --- 按 16×16 网格统计主色（看清贴图布局）---
console.log('\n每 16×16 格的主色：');
const gw = Math.ceil(width / 16), gh = Math.ceil(height / 16);
for (let gy = 0; gy < gh; gy++) {
  const row = [];
  for (let gx = 0; gx < gw; gx++) {
    const counts = new Map();
    for (let y = gy * 16; y < Math.min((gy + 1) * 16, height); y++) {
      for (let x = gx * 16; x < Math.min((gx + 1) * 16, width); x++) {
        const k = hex(at(x, y));
        counts.set(k, (counts.get(k) || 0) + 1);
      }
    }
    const top = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 2);
    row.push(`[${gx},${gy}] ${top.map(([c, n]) => `${c}×${n}`).join(' ')}`);
  }
  console.log('  ' + row.join('   '));
}

// --- 关键点采样 ---
console.log('\n关键点颜色：');
for (const [label, x, y] of [
  ['左上 (4,4)', 4, 4], ['左中 (4,16)', 4, 16], ['左下 (4,28)', 4, 28],
  ['右上 (16,4)', 16, 4], ['右中 (16,16)', 16, 16], ['右下 (16,28)', 16, 28],
  ['远右 (32,4)', 32, 4], ['远右 (48,4)', 48, 4], ['中心 (32,32)', 32, 32],
]) {
  if (x < width && y < height) console.log(`  ${label.padEnd(14)} ${hex(at(x, y))}`);
}
