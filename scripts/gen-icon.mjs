// Generates the app icon — a brand red diamond on a dark tile — as build-resources/
// icon.ico (multi-size, for electron-builder / the Windows exe + installer) and
// build-resources/icon.png (256px). Pure Node (built-in zlib) so there's no image
// dependency to install. Re-run with `node scripts/gen-icon.mjs` if the brand changes.
import zlib from 'node:zlib';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'build-resources');

// Brand palette (mirrors app/src/styles/tokens.css): dark tile gradient + accent diamond.
const TILE_TOP = [26, 30, 41]; // #1a1e29
const TILE_BOTTOM = [14, 15, 18]; // #0e0f12
const DIA_TOP = [226, 60, 46]; // --accent #e23c2e
const DIA_BOTTOM = [163, 31, 23]; // --accent-dark #a31f17

const lerp = (a, b, t) => a + (b - a) * t;
const clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);

// Color at point (x,y) for a `size`-px icon. Hard-edged; anti-aliasing comes from the
// supersampling in renderRGBA. Returns [r,g,b,a] 0-255.
function sample(x, y, size) {
  const cornerR = size * 0.18;
  const cx = size / 2;
  const cy = size / 2;
  const dHalf = size * 0.33; // diamond half-diagonal -> spans 66% of the tile

  // Rounded-rect tile: inside if within `cornerR` of the inset rectangle.
  const clampedX = Math.min(Math.max(x, cornerR), size - cornerR);
  const clampedY = Math.min(Math.max(y, cornerR), size - cornerR);
  const dx = x - clampedX;
  const dy = y - clampedY;
  if (dx * dx + dy * dy > cornerR * cornerR) return [0, 0, 0, 0]; // transparent outside the tile

  // Diamond membership (rotated square via Manhattan distance to center).
  const d = Math.abs(x - cx) / dHalf + Math.abs(y - cy) / dHalf;
  if (d <= 1) {
    const t = clamp01((y - (cy - dHalf)) / (2 * dHalf));
    let r = lerp(DIA_TOP[0], DIA_BOTTOM[0], t);
    let g = lerp(DIA_TOP[1], DIA_BOTTOM[1], t);
    let b = lerp(DIA_TOP[2], DIA_BOTTOM[2], t);
    // Subtle top sheen for a gem-like highlight on the upper facets.
    const sheen = clamp01((cy - y) / dHalf) * 0.2;
    r = lerp(r, 255, sheen);
    g = lerp(g, 255, sheen);
    b = lerp(b, 255, sheen);
    return [Math.round(r), Math.round(g), Math.round(b), 255];
  }

  // Tile background (vertical gradient).
  const t = y / size;
  return [
    Math.round(lerp(TILE_TOP[0], TILE_BOTTOM[0], t)),
    Math.round(lerp(TILE_TOP[1], TILE_BOTTOM[1], t)),
    Math.round(lerp(TILE_TOP[2], TILE_BOTTOM[2], t)),
    255,
  ];
}

// RGBA buffer for a `size`-px icon, 4x supersampled then box-averaged (anti-aliasing).
function renderRGBA(size) {
  const SS = 4;
  const out = Buffer.alloc(size * size * 4);
  for (let py = 0; py < size; py++) {
    for (let px = 0; px < size; px++) {
      let ar = 0;
      let ag = 0;
      let ab = 0;
      let aa = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const [r, g, b, a] = sample(px + (sx + 0.5) / SS, py + (sy + 0.5) / SS, size);
          // Premultiply by alpha so transparent corners don't bleed dark into the edge.
          const af = a / 255;
          ar += r * af;
          ag += g * af;
          ab += b * af;
          aa += a;
        }
      }
      const n = SS * SS;
      const alpha = aa / n;
      const i = (py * size + px) * 4;
      if (alpha === 0) {
        out[i] = out[i + 1] = out[i + 2] = out[i + 3] = 0;
      } else {
        // Un-premultiply back to straight alpha for PNG.
        const af = alpha / 255;
        out[i] = Math.round(ar / n / af);
        out[i + 1] = Math.round(ag / n / af);
        out[i + 2] = Math.round(ab / n / af);
        out[i + 3] = Math.round(alpha);
      }
    }
  }
  return out;
}

// --- PNG encoding (RGBA, 8-bit) ---
const crcTable = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const t = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([t, data])), 0);
  return Buffer.concat([len, t, data, crc]);
}
function encodePng(rgba, size) {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // color type RGBA
  const stride = size * 4;
  const raw = Buffer.alloc((stride + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (stride + 1)] = 0; // filter: none
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, y * stride + stride);
  }
  const idat = zlib.deflateSync(raw, { level: 9 });
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0))]);
}

// --- ICO container (PNG-compressed entries, supported by Windows Vista+) ---
function encodeIco(entries) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2); // type: icon
  header.writeUInt16LE(entries.length, 4);
  const dir = Buffer.alloc(16 * entries.length);
  let offset = 6 + 16 * entries.length;
  const blobs = [];
  entries.forEach((e, i) => {
    const o = i * 16;
    dir[o] = e.size >= 256 ? 0 : e.size; // width (0 means 256)
    dir[o + 1] = e.size >= 256 ? 0 : e.size; // height
    dir[o + 2] = 0; // palette
    dir[o + 3] = 0; // reserved
    dir.writeUInt16LE(1, o + 4); // color planes
    dir.writeUInt16LE(32, o + 6); // bits per pixel
    dir.writeUInt32LE(e.png.length, o + 8);
    dir.writeUInt32LE(offset, o + 12);
    offset += e.png.length;
    blobs.push(e.png);
  });
  return Buffer.concat([header, dir, ...blobs]);
}

const ICO_SIZES = [16, 32, 48, 64, 128, 256];
fs.mkdirSync(OUT_DIR, { recursive: true });

const icoEntries = ICO_SIZES.map((size) => ({ size, png: encodePng(renderRGBA(size), size) }));
fs.writeFileSync(path.join(OUT_DIR, 'icon.ico'), encodeIco(icoEntries));
fs.writeFileSync(path.join(OUT_DIR, 'icon.png'), encodePng(renderRGBA(256), 256));

console.log(`wrote icon.ico (${ICO_SIZES.join(', ')}px) and icon.png (256px) to build-resources/`);
