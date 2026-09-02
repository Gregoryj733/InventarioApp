/**
 * Procesa el logo Supra Tool adjunto:
 * - Quita fondo blanco
 * - Genera logo completo (con nombre) para UI de Supra Parts
 * - Genera icono minimalista (solo la herramienta, sin texto) para launcher y top bar
 */
import sharp from 'sharp';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, '..');

const src = process.argv[2] || path.join(
  process.env.USERPROFILE || '',
  '.cursor/projects/c-Users-greg7-Projects-InventarioApp/assets',
  'c__Users_greg7_AppData_Roaming_Cursor_User_workspaceStorage_ef176f242692bde9edfc336fcab9fc0b_images_Logo_Tool-f202c75a-3643-4a4b-a89a-df4fdf901726.png'
);

const outDir = path.join(root, 'app/src/main/res/drawable-nodpi');

function lum(r, g, b) {
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function isWhite(r, g, b, a) {
  if (a < 10) return true;
  return lum(r, g, b) > 248 && Math.max(r, g, b) - Math.min(r, g, b) < 18;
}

async function loadRgba(file) {
  return sharp(file).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
}

function bounds(data, w, h) {
  let minX = w, minY = h, maxX = 0, maxY = 0;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4;
      if (data[i + 3] > 20 && !isWhite(data[i], data[i + 1], data[i + 2], data[i + 3])) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
      }
    }
  }
  return { minX, minY, maxX, maxY };
}

function extractRegion(data, w, h, box, pad = 8) {
  const minX = Math.max(0, box.minX - pad);
  const minY = Math.max(0, box.minY - pad);
  const maxX = Math.min(w - 1, box.maxX + pad);
  const maxY = Math.min(h - 1, box.maxY + pad);
  const cw = maxX - minX + 1;
  const ch = maxY - minY + 1;
  const out = Buffer.alloc(cw * ch * 4);
  for (let y = 0; y < ch; y++) {
    for (let x = 0; x < cw; x++) {
      const si = ((y + minY) * w + (x + minX)) * 4;
      const di = (y * cw + x) * 4;
      const r = data[si], g = data[si + 1], b = data[si + 2], a = data[si + 3];
      if (isWhite(r, g, b, a)) {
        out[di + 3] = 0;
      } else {
        out[di] = r;
        out[di + 1] = g;
        out[di + 2] = b;
        out[di + 3] = a;
      }
    }
  }
  return { buffer: out, width: cw, height: ch };
}

function boundsInRegion(data, w, h, box) {
  let minX = w, minY = h, maxX = 0, maxY = 0;
  for (let y = box.minY; y <= box.maxY; y++) {
    for (let x = box.minX; x <= box.maxX; x++) {
      const i = (y * w + x) * 4;
      if (data[i + 3] > 20 && !isWhite(data[i], data[i + 1], data[i + 2], data[i + 3])) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
      }
    }
  }
  return { minX, minY, maxX, maxY };
}

/** Corta justo antes del texto "Supra Tool" (evita restos de letras bajo el icono). */
function findIconBottomY(data, w, fullBox) {
  const contentH = fullBox.maxY - fullBox.minY + 1;
  const scanStart = fullBox.minY + Math.round(contentH * 0.30);
  const scanEnd = fullBox.minY + Math.round(contentH * 0.48);
  const rowCounts = [];
  for (let y = scanStart; y <= scanEnd; y++) {
    let count = 0;
    for (let x = fullBox.minX; x <= fullBox.maxX; x++) {
      const i = (y * w + x) * 4;
      if (data[i + 3] > 20 && !isWhite(data[i], data[i + 1], data[i + 2], data[i + 3])) {
        count++;
      }
    }
    rowCounts.push({ y, count });
  }
  let valleyY = fullBox.minY + Math.round(contentH * 0.40);
  let minCount = Infinity;
  for (const row of rowCounts) {
    if (row.count < minCount) {
      minCount = row.count;
      valleyY = row.y;
    }
  }
  // Un poco por encima del valle para no incluir ascendentes del texto.
  return Math.max(fullBox.minY, valleyY - Math.round(contentH * 0.04));
}

function removeBottomSpecks(buffer, width, height, maxLum = 120) {
  const trimRows = Math.max(3, Math.round(height * 0.18));
  const startRow = height - trimRows;
  for (let y = startRow; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      const r = buffer[i], g = buffer[i + 1], b = buffer[i + 2], a = buffer[i + 3];
      if (a < 20) continue;
      if (lum(r, g, b) < maxLum) {
        buffer[i + 3] = 0;
      }
    }
  }
  return buffer;
}

async function writePng(buffer, width, height, targetWidth, outPath) {
  const targetHeight = Math.round(targetWidth * (height / width));
  await sharp(buffer, { raw: { width, height, channels: 4 } })
    .resize(targetWidth, targetHeight, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png({ compressionLevel: 9 })
    .toFile(outPath);
  console.log('Wrote', outPath, `${targetWidth}x${targetHeight}`);
}

const { data, info } = await loadRgba(src);
const w = info.width;
const h = info.height;
const fullBox = bounds(data, w, h);
const full = extractRegion(data, w, h, fullBox, 12);

const iconBottomY = findIconBottomY(data, w, fullBox);
const iconBox = {
  minX: fullBox.minX,
  maxX: fullBox.maxX,
  minY: fullBox.minY,
  maxY: iconBottomY
};
let icon = extractRegion(data, w, h, iconBox, 8);
removeBottomSpecks(icon.buffer, icon.width, icon.height);
const tightIconBox = boundsInRegion(icon.buffer, icon.width, icon.height, {
  minX: 0,
  minY: 0,
  maxX: icon.width - 1,
  maxY: icon.height - 1
});
icon = extractRegion(icon.buffer, icon.width, icon.height, tightIconBox, 4);

await writePng(full.buffer, full.width, full.height, 900, path.join(outDir, 'logo_supra_parts.png'));
await writePng(icon.buffer, icon.width, icon.height, 512, path.join(outDir, 'logo_supra_parts_icon.png'));
await writePng(icon.buffer, icon.width, icon.height, 256, path.join(outDir, 'ic_launcher_supra_foreground.png'));
