/*
 * 极简 PNG 生成器：手写 IHDR/IDAT/IEND + zlib，避免引入任何依赖。
 * 运行：node tools/gen_textures.js
 */
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

// ---------- PNG 编码 ----------
const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
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
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0; // filter: none
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 6;  // color type: RGBA
  ihdr[10] = 0; // deflate
  ihdr[11] = 0; // filter
  ihdr[12] = 0; // no interlace
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------- 画布 ----------
class Canvas {
  constructor(size) {
    this.size = size;
    this.data = Buffer.alloc(size * size * 4, 0);
  }
  set(x, y, [r, g, b, a = 255]) {
    if (x < 0 || y < 0 || x >= this.size || y >= this.size) return;
    const i = (y * this.size + x) * 4;
    this.data[i] = r; this.data[i + 1] = g; this.data[i + 2] = b; this.data[i + 3] = a;
  }
  get(x, y) {
    const i = (y * this.size + x) * 4;
    return [this.data[i], this.data[i + 1], this.data[i + 2], this.data[i + 3]];
  }
  disc(cx, cy, radius, color) {
    for (let y = 0; y < this.size; y++) {
      for (let x = 0; x < this.size; x++) {
        const dx = x + 0.5 - cx, dy = y + 0.5 - cy;
        if (dx * dx + dy * dy <= radius * radius) this.set(x, y, color);
      }
    }
  }
  rect(x0, y0, x1, y1, color) {
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) this.set(x, y, color);
  }
  /** 最近邻放大，用于生成 mod 图标 */
  scaled(factor) {
    const out = new Canvas(this.size * factor);
    for (let y = 0; y < out.size; y++) {
      for (let x = 0; x < out.size; x++) {
        out.set(x, y, this.get(Math.floor(x / factor), Math.floor(y / factor)));
      }
    }
    return out;
  }
  save(file) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, encodePng(this.size, this.size, this.data));
    console.log('written', file);
  }
}

const WOOD_DARK = [96, 64, 32];
const WOOD = [140, 96, 48];
const WOOD_LIGHT = [176, 128, 72];
const RUBBER = [178, 34, 34];
const RUBBER_DARK = [122, 18, 18];
const RUBBER_LIGHT = [214, 64, 52];

// ---------- 球拍 16x16：斜握柄 + 红色胶皮拍面 ----------
function paddle() {
  const c = new Canvas(16);
  // 拍面（圆心偏右上）
  const cx = 10.2, cy = 5.4, r = 4.6;
  c.disc(cx, cy, r, RUBBER);
  // 胶皮边缘加深，做出厚度感
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const dx = x + 0.5 - cx, dy = y + 0.5 - cy;
      const d = Math.sqrt(dx * dx + dy * dy);
      if (d > r - 1.0 && d <= r) c.set(x, y, RUBBER_DARK);
    }
  }
  // 高光
  c.disc(cx - 1.4, cy - 1.4, 1.5, RUBBER_LIGHT);

  // 木柄：从拍面左下斜向左下
  const handle = [
    [8, 8], [8, 9], [7, 9], [7, 10], [6, 10], [6, 11],
    [5, 11], [5, 12], [4, 12], [4, 13], [3, 13], [3, 14], [2, 14], [2, 15],
  ];
  for (const [x, y] of handle) {
    c.set(x, y, WOOD);
    c.set(x + 1, y, WOOD_LIGHT);
    c.set(x, y + 1, WOOD_DARK);
    c.set(x + 1, y + 1, WOOD);
  }
  return c;
}

// ---------- 乒乓球 16x16：白球 + 橙色缝线 ----------
function ball() {
  const c = new Canvas(16);
  const cx = 8, cy = 8, r = 6;
  c.disc(cx, cy, r, [245, 245, 240]);
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const dx = x + 0.5 - cx, dy = y + 0.5 - cy;
      const d = Math.sqrt(dx * dx + dy * dy);
      if (d > r - 1.2 && d <= r) c.set(x, y, [196, 196, 190]);       // 边缘阴影
      else if (d <= r) {
        // 左下暗、右上亮
        const shade = 1 - 0.28 * ((dx + dy) / (2 * r)) - 0.5;
        const base = [246, 246, 240];
        c.set(x, y, base.map((v, i) => Math.max(0, Math.min(255, Math.round(v * (1 + shade * 0.18))))));
      }
    }
  }
  // 橙色缝线（一条弧）
  for (let y = 0; y < 16; y++) {
    const t = (y - cy) / r;
    if (Math.abs(t) > 0.95) continue;
    const x = Math.round(cx + r * 0.62 - 1.6 * Math.abs(t));
    c.set(x, y, [226, 122, 40]);
    c.set(x + 1, y, [200, 100, 30, 140]);
  }
  return c;
}

// ---------- 图标 128x128 ----------
function icon() {
  const out = new Canvas(128);
  // 背景：深色球台
  out.rect(0, 0, 127, 127, [22, 40, 52]);
  out.rect(0, 0, 127, 3, [40, 70, 90]);
  out.rect(0, 124, 127, 127, [40, 70, 90]);
  // 放大后的球拍（7 倍 = 112）
  const p = paddle().scaled(7);
  for (let y = 0; y < 112; y++) {
    for (let x = 0; x < 112; x++) {
      const px = p.get(x, y);
      if (px[3] > 0) out.set(x + 6, y + 8, px);
    }
  }
  // 右下角一颗球
  const b = ball().scaled(2);
  for (let y = 0; y < 32; y++) {
    for (let x = 0; x < 32; x++) {
      const px = b.get(x, y);
      if (px[3] > 0) out.set(x + 88, y + 88, px);
    }
  }
  return out;
}

const root = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'pingpong');
paddle().save(path.join(root, 'textures', 'item', 'pingpong_paddle.png'));
ball().save(path.join(root, 'textures', 'item', 'pingpong_ball.png'));
ball().save(path.join(root, 'textures', 'entity', 'pingpong_ball.png'));
icon().save(path.join(root, 'icon.png'));
