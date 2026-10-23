// Offline SVG decoding for native ImageIO. Originals, bindings and licenses stay in shared/.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const sharp = require('sharp');
const root = path.resolve(__dirname, '../..');
const media = path.join(root, 'shared/assets/f1-media');
const out = path.join(root, 'ios/PitPitch/Resources/MediaDerived');
const sha = data => crypto.createHash('sha256').update(data).digest('hex');
(async () => {
  fs.mkdirSync(out, { recursive: true });
  const catalog = JSON.parse(fs.readFileSync(path.join(media, 'catalog.json')));
  const manifest = {};
  for (const row of Object.values(catalog)) {
    if (!row.file.endsWith('.svg') || manifest[row.file]) continue;
    const input = fs.readFileSync(path.join(media, row.file));
    if (sha(input) !== row.sha256) throw new Error(`Changed SVG: ${row.file}`);
    const output = row.file.replace(/\.svg$/, '.png');
    const png = await sharp(input, {density: 288}).resize({width: 608, height: 432, fit: 'inside', withoutEnlargement: false}).png().toBuffer();
    fs.writeFileSync(path.join(out, output), png);
    manifest[row.file] = { source: `shared/assets/f1-media/${row.file}`, sourceSHA256: sha(input), file: output, sha256: sha(png), encoding: 'sharp SVG rasterization; density 288; fit inside 608x432; original aspect and alpha' };
  }
  fs.writeFileSync(path.join(out, 'derived-index.json'), JSON.stringify(manifest, null, 2) + '\n');
  console.log(`Prepared ${Object.keys(manifest).length} transparent iOS track images offline`);
})().catch(error => { console.error(error); process.exitCode = 1; });
