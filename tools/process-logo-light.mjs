import sharp from 'sharp';

const src = process.argv[2];
const outLight = process.argv[3];

function lum(r, g, b) { return 0.2126 * r + 0.7152 * g + 0.0722 * b; }
function sat(r, g, b) {
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  return max === 0 ? 0 : (max - min) / max;
}

const { data, info } = await sharp(src).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
const w = info.width, h = info.height;
const idx = (x, y) => (y * w + x) * 4;

for (let y = 0; y < h; y++) {
  for (let x = 0; x < w; x++) {
    const i = idx(x, y);
    if (data[i + 3] < 10) continue;
    const l = lum(data[i], data[i + 1], data[i + 2]);
    const s = sat(data[i], data[i + 1], data[i + 2]);
    if (l > 230 && s < 0.05) data[i + 3] = 0;
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
  .toFile(outLight);

console.log('light logo', cw, 'x', ch, 'aspect', (cw / ch).toFixed(2));
