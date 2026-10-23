#!/usr/bin/env node
// Render code-defined promotional layouts around genuine, unmodified app captures.
// Requires sharp. Usage: node render_readme_artwork.cjs <capture-directory> <output-directory>
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const source = path.resolve(process.argv[2] || 'tmp/validation/readme-promo/screens');
const output = path.resolve(process.argv[3] || 'artwork/readme');
const version = process.argv[4] || fs.readFileSync(path.resolve(__dirname, '../../android/app/build.gradle.kts'), 'utf8').match(/versionName = "([^"]+)"/)[1];
const names = ['home', 'football-schedule', 'football-standings', 'team', 'f1-calendar', 'f1-drivers', 'f1-constructors'];
const data = bytes => `data:image/png;base64,${bytes.toString('base64')}`;
const screen = (image, x, y, width, id) => {
  const height = width * 2400 / 1080;
  return `<defs><clipPath id="${id}"><rect x="${x}" y="${y}" width="${width}" height="${height}" rx="28"/></clipPath></defs>
    <rect x="${x - 9}" y="${y - 9}" width="${width + 18}" height="${height + 18}" rx="36" fill="#17232b"/>
    <image href="${image}" x="${x}" y="${y}" width="${width}" height="${height}" clip-path="url(#${id})"/>`;
};
(async () => {
  fs.mkdirSync(output, { recursive: true });
  const captures = {};
  for (const name of names) {
    const input = fs.readFileSync(path.join(source, `${name}.png`));
    const meta = await sharp(input).metadata();
    if (meta.width !== 1080 || meta.height !== 2400) throw Error(`Unexpected screenshot dimensions: ${name}`);
    captures[name] = await sharp(input).resize(720, 1600).png({ compressionLevel: 9 }).toBuffer();
    // Web-sized originals are available directly by opening each README image.
    fs.writeFileSync(path.join(output, `${name}.png`), captures[name]);
  }
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1040" viewBox="0 0 1800 1040">
  <defs>
    <linearGradient id="back" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#fbfcfa"/><stop offset="1" stop-color="#edf4f1"/></linearGradient>
    <radialGradient id="red"><stop stop-color="#e10600" stop-opacity=".09"/><stop offset="1" stop-color="#e10600" stop-opacity="0"/></radialGradient>
    <radialGradient id="green"><stop stop-color="#15965b" stop-opacity=".11"/><stop offset="1" stop-color="#15965b" stop-opacity="0"/></radialGradient>
  </defs>
  <rect width="1800" height="1040" rx="36" fill="url(#back)"/>
  <circle cx="1650" cy="220" r="440" fill="url(#red)"/><circle cx="990" cy="1000" r="520" fill="url(#green)"/>
  <path d="M910 0L630 1040M990 0L710 1040" stroke="#15965b" stroke-opacity=".035" stroke-width="26"/>
  <g font-family="PingFang SC, Microsoft YaHei, sans-serif">
    <text x="90" y="180" font-family="Helvetica Neue, sans-serif" font-weight="900" font-style="italic" font-size="84" letter-spacing="-5"><tspan fill="#e10600">Pit</tspan><tspan fill="#15965b">Pitch</tspan></text>
    <text x="94" y="230" fill="#6a7973" font-size="17" letter-spacing="4">FOOTBALL  /  F1  /  ANDROID</text>
    <text x="88" y="390" fill="#17232b" font-size="78" font-weight="650">足球与 F1</text>
    <text x="88" y="490" fill="#17232b" font-size="78" font-weight="650">都在这里。</text>
    <rect x="94" y="536" width="46" height="5" rx="2.5" fill="#e10600"/><rect x="148" y="536" width="66" height="5" rx="2.5" fill="#15965b"/>
    <text x="94" y="602" fill="#56665f" font-size="25">下一场赛程、赛季积分与主队阵容</text>
    <text x="94" y="646" fill="#56665f" font-size="25">离线回看历史，开赛前预约提醒。</text>
    <g fill="#17232b" font-size="38" font-weight="600"><text x="94" y="776">7</text><text x="300" y="776">2020+</text><text x="548" y="776">5 min</text></g>
    <g fill="#6a7973" font-size="18"><text x="94" y="814">欧洲足球赛事</text><text x="300" y="814">历史赛季归档</text><text x="548" y="814">开赛前应用提醒</text></g>
    <path d="M254 739v86M500 739v86" stroke="#d7e1dc"/>
    <text x="94" y="946" fill="#6a7973" font-size="17">真实应用截图 · 本地开发版 ${version}</text>
  </g>
  ${screen(data(captures.home), 920, 100, 344, 'home')}
  ${screen(data(captures['f1-calendar']), 1322, 178, 344, 'f1')}
  <g font-family="PingFang SC, sans-serif" font-size="17" fill="#6a7973"><text x="1035" y="924">赛事首页</text><text x="1431" y="1001">F1 比赛周末</text></g>
  </svg>`;
  await sharp(Buffer.from(svg)).png({ compressionLevel: 9 }).toFile(path.join(output, 'cover.png'));
  // Keep the editable intermediate with temporary validation files, outside publication assets.
  fs.writeFileSync(path.join(source, '..', 'cover.svg'), svg);
  console.log(`Rendered ${names.length} screenshots and a 1800 × 1040 cover into ${output}`);
})();
