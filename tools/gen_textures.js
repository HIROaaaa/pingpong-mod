/*
 * 极简 PNG 生成器：手写 IHDR/IDAT/IEND + zlib，避免引入任何依赖。
 * 运行：node tools/gen_textures.js
 *
 * 生成清单：
 *   textures/item/pingpong_ball.png     16x16  乒乓球（物品 + 实体共用）
 *   textures/entity/pingpong_ball.png   16x16  同上（实体用）
 *   textures/item/pingpong_paddle.png   32x32  球拍 3D 模型贴图集（红胶皮/黑胶皮/木色/柄色 四象限）
 *   textures/item/pingpong_table.png    16x16  球台的物品图标
 *   textures/block/table_top.png        16x16  蓝色台面
 *   textures/block/table_leg.png        16x16  深色桌腿
 *   textures/block/table_line.png       16x16  白色边线
 *   textures/block/table_net.png        16x16  白色球网（带透明孔，需 cutout 渲染层）
 *   icon.png                            128x128 Mod 图标
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
  /** 把另一张画布贴到 (ox, oy) */
  blit(src, ox, oy) {
    for (let y = 0; y < src.size; y++) {
      for (let x = 0; x < src.size; x++) {
        const px = src.get(x, y);
        if (px[3] > 0) this.set(x + ox, y + oy, px);
      }
    }
  }
  save(file, width = this.size, height = this.size) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, encodePng(width, height, this.data));
    console.log('written', file);
  }
}

const WOOD_DARK = [96, 64, 32];
const WOOD = [140, 96, 48];
const WOOD_LIGHT = [176, 128, 72];
const RUBBER = [178, 34, 34];
const RUBBER_DARK = [122, 18, 18];
const RUBBER_LIGHT = [214, 64, 52];
// 蓝色球台（真实比赛用台是深蓝）
const TABLE_BLUE = [22, 74, 148];
const TABLE_BLUE_DARK = [16, 56, 116];

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
        const shade = 1 - 0.28 * ((dx + dy) / (2 * r)) - 0.5;
        const base = [246, 246, 240];
        c.set(x, y, base.map((v) => Math.max(0, Math.min(255, Math.round(v * (1 + shade * 0.18))))));
      }
    }
  }
  for (let y = 0; y < 16; y++) {
    const t = (y - cy) / r;
    if (Math.abs(t) > 0.95) continue;
    const x = Math.round(cx + r * 0.62 - 1.6 * Math.abs(t));
    c.set(x, y, [226, 122, 40]);
    c.set(x + 1, y, [200, 100, 30, 140]);
  }
  return c;
}

/** 胶皮纹理：底色 + 细颗粒点，做出涩涩的橡胶感 */
function rubberPatch(base, dot) {
  const c = new Canvas(16);
  c.rect(0, 0, 15, 15, base);
  for (let y = 1; y < 16; y += 3) {
    for (let x = 1; x < 16; x += 3) {
      c.set(x, y, dot);
      c.set(x + 1, y + 1, dot);
    }
  }
  return c;
}

/** 木纹 */
function woodPatch(base, dark, light) {
  const c = new Canvas(16);
  c.rect(0, 0, 15, 15, base);
  for (let y = 0; y < 16; y++) {
    if (y % 5 === 0) c.rect(0, y, 15, y, dark);
    if (y % 7 === 3) c.rect(0, y, 15, y, light);
  }
  return c;
}

/**
 * 球拍 3D 模型贴图集 32x32（四象限，uv 以 0~32 像素为单位，见 models/item/pingpong_paddle.json）：
 *
 * ```
 *   0,0  ┌────────────┬────────────┐
 *        │ 红胶皮(面) │ 黑胶皮(面) │   ← 各 16x16，uv [0,0,16,16] / [16,0,32,16]
 *  16,0  ├────────────┼────────────┤
 *        │ 木芯(柄)   │ 木芯(侧面) │   ← 各 16x16，uv [0,16,16,32] / [16,16,32,32]
 *        └────────────┴────────────┘
 * ```
 *
 * 胶皮面从旧的 8x8 提到 16x16：模型里拍面是 12x10 格像素，16x16 才画得下
 * 「颗粒质感 + 边缘暗化」，斜着看才有胶皮的样子（需求 1：球拍要立体、不是纸片）。
 */
function paddleAtlas() {
  /*
   * 【为什么这里不再有品红格】之前为了放"版本标记色"把图集扩到 64、在第 5 格填了纯品红
   * （#FF2A8A），结果球拍上出现一大块刺眼的品红（标记色被采样到了）。
   * 现在渲染链路已经验证通过、标记完成使命，那一格改成**木芯色**：
   * 万一还有元素采样到那里，看到的也只是木头色，不会突兀。
   */
  const c = new Canvas(64, 32);
  c.blit(rubberPatch(RUBBER, RUBBER_DARK), 0, 0);              // [0,0,16,16]    正面红胶皮
  c.blit(rubberPatch([28, 28, 30], [52, 52, 56]), 16, 0);      // [16,0,32,16]   反面黑胶皮
  c.blit(woodPatch(WOOD, WOOD_DARK, WOOD_LIGHT), 0, 16);       // [0,16,16,32]   木芯（柄）
  c.blit(woodPatch(WOOD_DARK, [66, 44, 22], WOOD), 16, 16);    // [16,16,32,32]  木芯（侧面）
  c.rect(32, 0, 63, 31, WOOD);                                 // [32,0,64,32]   备用格（木芯色）
  return c;
}

/** 蓝色台面：深蓝底 + 极细横纹 */
function tableTop() {
  const c = new Canvas(16);
  c.rect(0, 0, 15, 15, TABLE_BLUE);
  for (let y = 0; y < 16; y += 4) c.rect(0, y, 15, y, TABLE_BLUE_DARK);
  return c;
}

/** 白色边线 / 中线：纯白 */
function tableLine() {
  const c = new Canvas(16);
  c.rect(0, 0, 15, 15, [242, 242, 238]);
  return c;
}

/** 球网：白色网线 + 透明孔 */
function tableNet() {
  const c = new Canvas(16);
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const isThread = y === 0 || y === 15 || x % 3 === 0 || y % 4 === 0;
      if (isThread) c.set(x, y, [236, 236, 232]);
    }
  }
  return c;
}

/** 球台的物品图标 16x16：台面 + 网 + 白线 */
function tableIcon() {
  const c = new Canvas(16);
  c.rect(0, 0, 15, 15, [0, 0, 0, 0]);
  // 台面（透视成一个梯形太麻烦，画成俯视 + 一点立体感）
  c.rect(1, 3, 14, 12, TABLE_BLUE);
  c.rect(1, 3, 14, 4, [30, 92, 172]);      // 远边亮一点
  c.rect(1, 11, 14, 12, TABLE_BLUE_DARK);  // 近边暗一点
  // 白色边线
  c.rect(1, 3, 14, 3, [242, 242, 238]);
  c.rect(1, 12, 14, 12, [242, 242, 238]);
  c.set(1, 4, [242, 242, 238]); c.set(1, 11, [242, 242, 238]);
  c.set(14, 4, [242, 242, 238]); c.set(14, 11, [242, 242, 238]);
  // 中线
  c.rect(7, 4, 8, 11, [242, 242, 238]);
  // 球网（横跨中间）
  for (let x = 1; x <= 14; x += 1) {
    c.set(x, 7, [236, 236, 232]);
    c.set(x, 8, [220, 220, 216]);
  }
  c.set(3, 6, [236, 236, 232]); c.set(11, 6, [236, 236, 232]);
  // 桌腿
  c.rect(2, 13, 3, 15, [70, 48, 26]);
  c.rect(12, 13, 13, 15, [70, 48, 26]);
  return c;
}

/** 图标里用的平面球拍小图（不从 3D 贴图集里裁，避免模糊） */
function paddleSprite() {
  const c = new Canvas(16);
  const cx = 10.2, cy = 5.4, r = 4.6;
  c.disc(cx, cy, r, RUBBER);
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const dx = x + 0.5 - cx, dy = y + 0.5 - cy;
      const d = Math.sqrt(dx * dx + dy * dy);
      if (d > r - 1.0 && d <= r) c.set(x, y, RUBBER_DARK);
    }
  }
  c.disc(cx - 1.4, cy - 1.4, 1.5, RUBBER_LIGHT);
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

/** Mod 图标 128x128：蓝色球台 + 球拍 + 球 */
function icon() {
  const out = new Canvas(128);
  out.rect(0, 0, 127, 127, [12, 24, 36]);
  // 蓝色台面
  out.rect(6, 30, 121, 106, TABLE_BLUE);
  out.rect(6, 30, 121, 34, [30, 92, 172]);
  out.rect(6, 102, 121, 106, TABLE_BLUE_DARK);
  out.rect(6, 30, 121, 33, [242, 242, 238]);
  out.rect(6, 103, 121, 106, [242, 242, 238]);
  out.rect(6, 34, 10, 102, [242, 242, 238]);
  out.rect(117, 34, 121, 102, [242, 242, 238]);
  out.rect(61, 34, 66, 102, [242, 242, 238]);
  // 球网
  for (let x = 6; x <= 121; x++) {
    out.set(x, 65, [236, 236, 232]);
    out.set(x, 68, [228, 228, 224]);
  }
  out.rect(6, 61, 121, 61, [248, 248, 244]);
  // 球拍
  const p = paddleSprite().scaled(5);
  out.blit(p, 10, 4);
  // 球
  out.blit(ball().scaled(3), 88, 8);
  return out;
}

const root = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'pingpong');
const blockDir = path.join(root, 'textures', 'block');
const itemDir = path.join(root, 'textures', 'item');

ball().save(path.join(itemDir, 'pingpong_ball.png'));
ball().save(path.join(root, 'textures', 'entity', 'pingpong_ball.png'));
paddleAtlas().save(path.join(itemDir, 'pingpong_paddle.png'));
tableIcon().save(path.join(itemDir, 'pingpong_table.png'));
tableTop().save(path.join(blockDir, 'table_top.png'));
woodPatch(WOOD_DARK, [66, 44, 22], WOOD).save(path.join(blockDir, 'table_leg.png'));
tableLine().save(path.join(blockDir, 'table_line.png'));
tableNet().save(path.join(blockDir, 'table_net.png'));
icon().save(path.join(root, 'icon.png'));
