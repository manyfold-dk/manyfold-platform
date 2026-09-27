const fs = require('fs');
const path = require('path');
const http = require('http');
const zlib = require('zlib');
const { execFileSync } = require('child_process');

// Playwright is not a dependency of this repository (it has no root
// package.json), so a plain require() from this directory fails. Look next to
// this script first, then in the current directory's node_modules and in the
// global npm root.
function loadPlaywright() {
  const searchPaths = [__dirname, process.cwd()];
  try {
    const globalRoot = execFileSync('npm', ['root', '-g'], { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
    if (globalRoot) searchPaths.push(globalRoot);
  } catch (e) {
    // npm is not on PATH; the local search paths remain
  }
  try {
    return require(require.resolve('playwright', { paths: searchPaths }));
  } catch (e) {
    if (e.code !== 'MODULE_NOT_FOUND') throw e;
    console.error('Playwright not found. Install it once with:');
    console.error('  npm install --global playwright && npx playwright install chromium');
    console.error('or run this script from a directory whose node_modules contains playwright.');
    process.exit(1);
  }
}
const { chromium } = loadPlaywright();

const DRAWIO_FILE = process.argv[2];
if (!DRAWIO_FILE) {
  console.error('Usage: node drawio-to-png.js <input.drawio> [output.drawio.png]');
  process.exit(1);
}
const OUTPUT_FILE = process.argv[3] || DRAWIO_FILE.replace('.drawio', '.drawio.png');
const VIEWER_JS_PATH = '/tmp/viewer-static.min.js';

if (!fs.existsSync(VIEWER_JS_PATH)) {
  console.error(`Viewer JS not found. Run: curl -sL "https://viewer.diagrams.net/js/viewer-static.min.js" -o ${VIEWER_JS_PATH}`);
  process.exit(1);
}

// Embed draw.io XML into PNG tEXt chunk so it's a proper .drawio.png
function embedDrawioXml(pngBuffer, xmlContent) {
  // PNG structure: 8-byte signature, then chunks
  // Each chunk: 4-byte length, 4-byte type, data, 4-byte CRC
  // We insert a tEXt chunk with key "mxfile" before IEND

  const signature = pngBuffer.slice(0, 8);
  const keyword = 'mxfile';
  const textData = Buffer.concat([
    Buffer.from(keyword, 'latin1'),
    Buffer.from([0]), // null separator
    Buffer.from(xmlContent, 'utf-8')
  ]);

  const chunkType = Buffer.from('tEXt', 'ascii');
  const chunkLength = Buffer.alloc(4);
  chunkLength.writeUInt32BE(textData.length);

  // Calculate CRC32 over type + data
  const crcData = Buffer.concat([chunkType, textData]);
  const crc = crc32(crcData);
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc >>> 0);

  const textChunk = Buffer.concat([chunkLength, chunkType, textData, crcBuf]);

  // Find IEND chunk (last 12 bytes of a valid PNG)
  const iendOffset = pngBuffer.length - 12;
  const beforeIend = pngBuffer.slice(0, iendOffset);
  const iend = pngBuffer.slice(iendOffset);

  return Buffer.concat([beforeIend, textChunk, iend]);
}

// CRC32 implementation for PNG chunks
function crc32(buf) {
  let table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let j = 0; j < 8; j++) {
      c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    }
    table[i] = c;
  }
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) {
    crc = table[(crc ^ buf[i]) & 0xFF] ^ (crc >>> 8);
  }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

// Return the first diagram's <mxGraphModel> XML. draw.io stores a diagram
// either as plain XML or compressed: the <diagram> element then holds
// base64(deflateRaw(encodeURIComponent(xml))).
function extractGraphModel(fileContent) {
  const modelPattern = /<mxGraphModel[\s\S]*<\/mxGraphModel>/;
  const plain = fileContent.match(modelPattern);
  if (plain) return plain[0];

  const diagram = fileContent.match(/<diagram\b[^>]*>([\s\S]*?)<\/diagram>/);
  if (!diagram) return null;
  const encoded = diagram[1].replace(/\s+/g, '');
  if (!encoded) return null;
  try {
    const inflated = zlib.inflateRawSync(Buffer.from(encoded, 'base64')).toString('utf-8');
    const decoded = decodeURIComponent(inflated);
    const compressed = decoded.match(modelPattern);
    return compressed ? compressed[0] : null;
  } catch (e) {
    console.error(`Could not decompress the diagram: ${e.message}`);
    return null;
  }
}

(async () => {
  const xmlContent = fs.readFileSync(DRAWIO_FILE, 'utf-8');
  const viewerJs = fs.readFileSync(VIEWER_JS_PATH, 'utf-8');

  const modelXml = extractGraphModel(xmlContent);
  if (!modelXml) {
    console.error('Could not find mxGraphModel in drawio file');
    process.exit(1);
  }
  const graphXml = modelXml.replace(/\n/g, ' ').replace(/\s{2,}/g, ' ');

  // Serve HTML via local HTTP server (viewer-static needs proper document context)
  const htmlContent = `<!DOCTYPE html>
<html><head><meta charset="utf-8">
<style>
  * { margin: 0; padding: 0; }
  body { background: white; overflow: hidden; }
</style>
</head><body>
<div id="graph-container"></div>
<script>
var GRAPH_XML = ${JSON.stringify(graphXml)};
var container = document.getElementById('graph-container');
var div = document.createElement('div');
div.className = 'mxgraph';
div.setAttribute('data-mxgraph', JSON.stringify({
  highlight: '#0000ff',
  nav: false,
  resize: true,
  toolbar: null,
  edit: null,
  xml: GRAPH_XML
}));
container.appendChild(div);
</script>
<script src="/viewer.js"></script>
<script>
// Signal when rendering is done
function checkRendered() {
  var svgs = document.querySelectorAll('svg');
  if (svgs.length > 0) {
    // Get the actual rendered size
    var gd = document.querySelector('.geDiagramContainer');
    if (gd) {
      document.title = 'DONE:' + gd.scrollWidth + ':' + gd.scrollHeight;
    } else {
      document.title = 'DONE:1600:1100';
    }
  } else {
    setTimeout(checkRendered, 200);
  }
}
setTimeout(checkRendered, 1000);
</script>
</body></html>`;

  // Start local server
  const server = http.createServer((req, res) => {
    if (req.url === '/viewer.js') {
      res.writeHead(200, { 'Content-Type': 'application/javascript' });
      res.end(viewerJs);
    } else {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(htmlContent);
    }
  });

  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  console.log(`Local server on port ${port}`);

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1800, height: 1400 });

  await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: 'networkidle', timeout: 30000 });

  // Wait for rendering
  try {
    await page.waitForFunction(() => document.title.startsWith('DONE:'), { timeout: 15000 });
    console.log('Diagram rendered: ' + await page.title());
  } catch (e) {
    console.log('Rendering timed out, proceeding with screenshot');
  }
  await page.waitForTimeout(500);

  // Get the diagram container bounds
  const bounds = await page.evaluate(() => {
    var gd = document.querySelector('.geDiagramContainer');
    if (gd) {
      var rect = gd.getBoundingClientRect();
      return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
    }
    var svg = document.querySelector('svg');
    if (svg) {
      var rect = svg.getBoundingClientRect();
      return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
    }
    return null;
  });

  let pngBuffer;
  if (bounds && bounds.width > 50 && bounds.height > 50) {
    console.log(`Diagram size: ${Math.round(bounds.width)}x${Math.round(bounds.height)}`);
    pngBuffer = await page.screenshot({
      clip: {
        x: Math.max(0, bounds.x - 15),
        y: Math.max(0, bounds.y - 15),
        width: Math.min(bounds.width + 30, 1800),
        height: Math.min(bounds.height + 30, 1400)
      },
      type: 'png'
    });
  } else {
    console.log('Using full page screenshot');
    pngBuffer = await page.screenshot({ type: 'png', fullPage: true });
  }

  // Embed the draw.io XML into the PNG metadata
  const drawioXml = xmlContent.trim();
  const finalPng = embedDrawioXml(pngBuffer, drawioXml);

  fs.writeFileSync(OUTPUT_FILE, finalPng);
  console.log(`Saved: ${OUTPUT_FILE} (${finalPng.length} bytes, drawio XML embedded)`);

  await browser.close();
  server.close();
})();
