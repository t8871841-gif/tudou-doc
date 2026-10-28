// Render eye-icon.svg at every pixel size the Android icon set needs.
// Vector rendering per size keeps the thin rings crisp at 48px; do not
// downscale one master render.
//
// Prerequisite (once, outside the repo — node_modules is never committed):
//   mkdir /tmp/eye-icon && cd /tmp/eye-icon && npm init -y && npm i sharp
// Usage:
//   node render_eye_icon.mjs <svg> <out-dir>
const sharp = require("sharp");
const fs = require("fs");
const path = require("path");

const [svg, outDir] = process.argv.slice(2);
if (!svg || !outDir) {
  console.error("usage: node render_eye_icon.mjs <svg> <out-dir>");
  process.exit(2);
}
const sizes = [48, 72, 96, 108, 144, 162, 192, 216, 324, 432];
fs.mkdirSync(outDir, { recursive: true });
(async () => {
  for (const s of sizes) {
    await sharp(svg).resize(s, s).png().toFile(path.join(outDir, `eye-${s}.png`));
    console.log(`eye-${s}.png`);
  }
})();
