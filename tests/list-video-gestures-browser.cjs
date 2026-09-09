const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

(async () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pichome-gesture-'));
    const media = path.join(directory, 'test.webm');
    const atlasMode = process.env.ATLAS_TEST === '1';
    const atlasFile = path.join(directory, 'atlas.png');
    execFileSync('ffmpeg', ['-v', 'error', '-f', 'lavfi', '-i', 'testsrc=size=320x180:rate=30', '-t', '3', '-c:v', 'libvpx', '-an', media]);
    if (atlasMode) execFileSync('ffmpeg', ['-v', 'error', '-i', media, '-vf', 'scale=64:36,tile=5x6', '-frames:v', '1', atlasFile]);
    const browser = await chromium.launch({headless: true});
    try {
        const page = await browser.newPage({viewport: {width: 400, height: 800}, isMobile: true, hasTouch: true});
        await page.route('https://media.test/', route => route.fulfill({contentType: 'text/html', body: '<html></html>'}));
        await page.goto('https://media.test/');
        await page.route('https://media.test/video', route => {
            const data = fs.readFileSync(media);
            const range = /bytes=(\d+)-(\d*)/.exec(route.request().headers().range || '');
            const start = range ? Number(range[1]) : 0;
            const end = range && range[2] ? Math.min(data.length - 1, Number(range[2])) : data.length - 1;
            return route.fulfill({status: range ? 206 : 200, contentType: 'video/webm', headers: {'accept-ranges': 'bytes', ...(range ? {'content-range': `bytes ${start}-${end}/${data.length}`} : {})}, body: data.subarray(start, end + 1)});
        });
        await page.route('https://media.test/index.php?*', route => {
            const url = new URL(route.request().url());
            if (url.searchParams.get('operation') === 'frameAtlasManifest') return route.fulfill({contentType: 'application/json', headers: {'access-control-allow-origin': '*'}, body: JSON.stringify({success: true, revision: 'test', fps: 30, duration: 3, segmentSeconds: 1, maxSegment: 2, framesPerSegment: 30, cellWidth: 64, cellHeight: 36, columns: 5})});
            return route.fulfill({contentType: 'image/png', body: fs.readFileSync(atlasFile)});
        });
        await page.setContent('<base href="https://media.test/"><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0;height:3000px}video{width:100%;height:100%}</style><div id="app"></div>');
        if (atlasMode) await page.addScriptTag({path: path.join(__dirname, '../web/dzz/details/template/pc/assets/js/frame-scrub.js')});
        await page.addScriptTag({path: path.join(__dirname, '../web/static/vue/vue@3/vue.prod.js')});
        const source = fs.readFileSync(path.join(__dirname, '../web/static/vue_components/ImageLayout/index.js'), 'utf8');
        const component = source.slice(source.indexOf('var rt={'), source.indexOf('var lt=', source.indexOf('var rt={')));
        await page.addScriptTag({content: 'const {ref,reactive}=Vue;' + component});
        await page.evaluate(() => {
            window.app = Vue.createApp({
                setup() { const api = rt.setup({ImageData: {rid: 'test', fps: 30, dpath: window.PichomeFrameScrub ? 'test' : '', mediaplayerpath: 'https://media.test/video'}}); window.api = api; return api; },
                template: '<div ref="DomBox" @pointerdown="Pointerdown" @pointerup="Pointerup" @pointercancel="Pointercancel" style="height:240px;background:#111"><div ref="DomVideoBox" style="height:100%"></div></div>'
            });
            app.mount('#app');
        });
        await page.locator('#app').tap({position: {x: 150, y: 100}});
        await page.waitForFunction(() => document.querySelector('video').currentTime > 0.1);
        assert.equal(await page.locator('video').evaluate(video => video.muted), false);
        await page.locator('video').tap();
        assert(await page.locator('video').evaluate(video => video.paused));
        await page.locator('video').evaluate(video => { video.currentTime = 1; });
        await page.waitForFunction(() => !document.querySelector('video').seeking);
        if (atlasMode) await page.waitForFunction(() => Object.values(document.querySelector('video').__pichomeDesktopFrameScrub.atlas.entries).filter(entry => entry.status === 'ready').length === 3);
        const client = await page.context().newCDPSession(page);
        const touch = (type, points) => client.send('Input.dispatchTouchEvent', {type, touchPoints: points.map(([x, y]) => ({id: 1, x, y}))});
        await touch('touchStart', [[100, 80]]);
        await touch('touchMove', [[121, 170]]);
        if (atlasMode) {
            await page.waitForFunction(() => Math.abs(document.querySelector('video').__pichomeDesktopFrameScrub.previewTime - 1.1) < 0.001);
            assert.equal(await page.locator('video').evaluate(video => video.currentTime), 1);
            assert(await page.locator('.pichome-desktop-frame-overlay').isVisible());
            await touch('touchMove', [[86, 175]]);
            await page.waitForFunction(() => Math.abs(document.querySelector('video').__pichomeDesktopFrameScrub.previewTime - 28 / 30) < 0.001);
            assert.equal(await page.locator('video').evaluate(video => video.currentTime), 1);
            await touch('touchMove', [[121, 170]]);
            await page.waitForFunction(() => Math.abs(document.querySelector('video').__pichomeDesktopFrameScrub.previewTime - 1.1) < 0.001);
        }
        await touch('touchEnd', []);
        await page.waitForFunction(() => !document.querySelector('video').seeking);
        const result = await page.locator('video').evaluate(video => ({time: video.currentTime, paused: video.paused, scroll: window.scrollY}));
        assert(Math.abs(result.time - 1.1) < (atlasMode ? 1 / 30 : 0.001), JSON.stringify(result));
        assert(result.paused);
        assert.equal(result.scroll, 0);
        await page.locator('video').tap();
        await page.waitForFunction(() => !document.querySelector('video').paused);
        await page.locator('video').tap();
        await page.waitForTimeout(300);
        assert(await page.locator('video').evaluate(video => video.paused));
        await touch('touchStart', [[300, 650]]);
        await touch('touchMove', [[300, 400]]);
        await touch('touchEnd', []);
        await page.waitForFunction(() => window.scrollY > 0);
        await page.evaluate(() => app.unmount());
        console.log('PASS real video: ' + (atlasMode ? 'atlas preheat, forward/reverse canvas preview without video seeks, release commit, ' : '') + 'tap play/pause, diagonal frame steps, locked video scroll, outside scrolling, cleanup');
    } finally {
        await browser.close();
        fs.rmSync(directory, {recursive: true, force: true});
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
