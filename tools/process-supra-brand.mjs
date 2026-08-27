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
  root,
  '.cursor/projects/c-Users-greg7-Projects-InventarioApp/assets',
  'c__Users_greg7_AppData_Roaming_Cursor_User_workspaceStorage_ef176f242692bde9edfc336fcab9fc0b_images_Logo_Tool-cfd7428d-93ad-4d64-a8e7-acc382221d27.png'
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

// Icono: parte superior (~58% del alto del contenido, sin el texto "Supra Tool")
const iconBox = {
  minX: fullBox.minX,
  maxX: fullBox.maxX,
  minY: fullBox.minY,
  maxY: fullBox.minY + Math.round((fullBox.maxY - fullBox.minY) * 0.58)
};
const icon = extractRegion(data, w, h, iconBox, 10);

await writePng(full.buffer, full.width, full.height, 900, path.join(outDir, 'logo_supra_parts.png'));
await writePng(icon.buffer, icon.width, icon.height, 512, path.join(outDir, 'logo_supra_parts_icon.png'));
await writePng(icon.buffer, icon.width, icon.height, 256, path.join(outDir, 'ic_launcher_supra_foreground.png'));
