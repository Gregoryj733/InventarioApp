import sharp from 'sharp';

const src = process.argv[2];
const outDark = process.argv[3];

function lum(r, g, b) { return 0.2126 * r + 0.7152 * g + 0.0722 * b; }
function sat(r, g, b) {
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  return max === 0 ? 0 : (max - min) / max;
}
function hue(r, g, b) {
  const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
  if (!d) return 0;
  let h;
  if (max === r) h = ((g - b) / d) % 6;
  else if (max === g) h = (b - r) / d + 2;
  else h = (r - g) / d + 4;
  h *= 60;
  if (h < 0) h += 360;
  return h;
}

const { data, info } = await sharp(src).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
const w = info.width, h = info.height;
const idx = (x, y) => (y * w + x) * 4;

for (let y = 0; y < h; y++) {
  for (let x = 0; x < w; x++) {
    const i = idx(x, y);
    if (data[i + 3] < 10) continue;
    let r = data[i], g = data[i + 1], b = data[i + 2];
    const l = lum(r, g, b), s = sat(r, g, b), hu = hue(r, g, b);
    if (l > 230 && s < 0.05) { data[i + 3] = 0; continue; }
    if (hu > 190 && hu < 250 && s > 0.2 && l < 175) {
      r = 248; g = 250; b = 252;
    } else if (s < 0.2 && l > 55 && l < 210) {
      r = Math.min(255, r + 30);
      g = Math.min(255, g + 30);
      b = Math.min(255, b + 30);
    }
    data[i] = r; data[i + 1] = g; data[i + 2] = b;
  }
}

let minX = w, minY = h, maxX = 0, maxY = 0;
for (let y = 0; y < h; y++) {
  for (let x = 0; x < w; x++) {
    if (data[idx(x, y) + 3] > 12) {
      if (x < minX) minX = x;
      if (y < minY) minY = y;
      if (x > maxX) maxX = x;
      if (y > maxY) maxY = y;
    }
  }
}
const pad = 12;
minX = Math.max(0, minX - pad);
minY = Math.max(0, minY - pad);
maxX = Math.min(w - 1, maxX + pad);
maxY = Math.min(h - 1, maxY + pad);
const cw = maxX - minX + 1, ch = maxY - minY + 1;
const cropped = Buffer.alloc(cw * ch * 4);
for (let y = 0; y < ch; y++) {
  for (let x = 0; x < cw; x++) {
    const si = idx(x + minX, y + minY), di = (y * cw + x) * 4;
    cropped[di] = data[si];
    cropped[di + 1] = data[si + 1];
    cropped[di + 2] = data[si + 2];
    cropped[di + 3] = data[si + 3];
  }
}

await sharp(cropped, { raw: { width: cw, height: ch, channels: 4 } })
  .resize(Math.round(cw * 2), Math.round(ch * 2), { kernel: sharp.kernel.lanczos3 })
  .png({ compressionLevel: 9 })
  .toFile(outDark);

console.log('dark logo', cw, 'x', ch, 'aspect', (cw / ch).toFixed(2));
