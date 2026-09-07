const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const path = require('node:path');

(async () => {
    const browser = await chromium.launch({
        headless: true,
        executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || undefined
    });
    try {
        const page = await browser.newPage({viewport: {width: 400, height: 800}, isMobile: true, hasTouch: true, deviceScaleFactor: 1});
        await page.setContent('<meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0}.wrapper{width:240px;height:300px;overflow:hidden;transform:translate(40px,100px)}iframe{width:100%;height:100%;border:0}</style><div class="wrapper"><iframe></iframe></div>');
        const frame = page.frames()[1];
        await page.evaluate(() => {
            window.backgroundClicks = 0;
            window.removedDuringPointerup = 0;
            document.addEventListener('click', () => window.backgroundClicks++);
            document.addEventListener('pointerup', () => {
                if (!document.querySelector('.pichome-image-fullscreen')) window.removedDuringPointerup++;
            });
            const button = document.createElement('button');
            button.textContent = '分享/下载';
            button.style.cssText = 'position:fixed;left:250px;top:150px;width:150px;height:100px';
            document.body.appendChild(button);
        });
        await frame.setContent('<div class="detail-popup-content center"><div class="img-box"><img class="max" style="width:200px" src="data:image/svg+xml,%3Csvg xmlns=\'http://www.w3.org/2000/svg\' width=\'400\' height=\'800\'%3E%3Crect width=\'400\' height=\'800\' fill=\'red\'/%3E%3C/svg%3E"></div></div>');
        await frame.addScriptTag({path: path.join(__dirname, '../web/dzz/details/template/mobile/assets/js/image-fullscreen.js')});
        await frame.locator('img').tap();
        const overlay = page.locator('.pichome-image-fullscreen');
        await overlay.locator('img').evaluate(image => image.decode());
        assert.deepEqual(await overlay.boundingBox(), {x: 0, y: 0, width: 400, height: 800});
        const client = await page.context().newCDPSession(page);
        const touch = (type, points) => client.send('Input.dispatchTouchEvent', {type, touchPoints: points.map(([id, x, y]) => ({id, x, y}))});
        const transform = () => overlay.locator('img').evaluate(image => image.parentElement.style.transform);
        await touch('touchStart', [[1, 100, 400], [2, 300, 400]]);
        await touch('touchMove', [[1, 0, 400], [2, 400, 400]]);
        assert.equal(await transform(), 'translate3d(-200px, -400px, 0px) scale(2)');
        await touch('touchEnd', [[2, 400, 400]]);
        await touch('touchMove', [[1, 50, 480]]);
        assert.equal(await transform(), 'translate3d(-150px, -320px, 0px) scale(2)');
        await touch('touchEnd', []);
        assert.equal(await overlay.getByRole('button').count(), 0);
        assert.equal(await overlay.innerText(), '');
        await page.setViewportSize({width: 800, height: 400});
        await page.waitForFunction(() => document.querySelector('.pichome-image-fullscreen').style.width === '800px');
        assert.deepEqual(await overlay.boundingBox(), {x: 0, y: 0, width: 800, height: 400});
        await overlay.tap({position: {x: 300, y: 200}});
        assert.equal(await overlay.count(), 0);
        await page.waitForTimeout(400);
        assert.equal(await page.evaluate(() => window.backgroundClicks), 0);
        assert.equal(await page.evaluate(() => window.removedDuringPointerup), 0);
        assert.equal(await page.evaluate(() => document.body.inert), false);
        await page.setViewportSize({width: 400, height: 800});
        await page.evaluate(() => {
            window.dzzoffice = {WindowThis: {ImageDataParam: {data: ['first', 'video', 'second', 'denied', 'third'].map(dpath => ({dpath}))}}};
        });
        await frame.addScriptTag({path: path.join(__dirname, '../web/static/vue/vue@3/vue.prod.js')});
        await frame.evaluate(() => {
            const source = document.querySelector('img');
            const imageUrl = source.src;
            window.MOD_URL = 'index.php?mod=details';
            window.requests = [];
            window.axios = {post: async (url, {path}) => {
                window.requests.push(path);
                if (path === 'denied') return {data: {error: 'no permission'}};
                return {data: {resourcesdata: {opentype: path === 'video' ? 'video' : 'image', originalimg: imageUrl, rid: path}}};
            }};
            const element = document.createElement('div');
            element.id = 'dzzoffice';
            document.querySelector('.detail-popup-content').remove();
            document.body.appendChild(element);
            const app = Vue.createApp({
                data: () => ({path: 'first', paths: ['first'], ridIndex: 0, imagePath: 'first', imageUrl}),
                methods: {
                    async CommonOrder(data) {
                        if (data.opentype !== 'image') throw new Error('Video rendered');
                        this.imagePath = this.path;
                        await this.$nextTick();
                    }
                },
                template: '<div id="DetailPopup"><div class="detail-popup-content center"><div class="img-box"><img class="max" :key="imagePath" :src="imageUrl" style="width:200px"></div></div></div>'
            });
            window.detail = window.__pichomeImageDetail = app.mount(element);
            if (app._instance || document.querySelector('#DetailPopup').__vueParentComponent) throw new Error('Expected production Vue without private debug instances');
        });
        await touch('touchStart', [[1, 200, 240]]);
        await touch('touchMove', [[1, 80, 240]]);
        await touch('touchEnd', []);
        await frame.waitForFunction(() => window.detail.path === 'second');
        assert.deepEqual(await frame.evaluate(() => window.requests), ['video', 'second']);
        assert.equal(await overlay.count(), 0);
        await page.waitForTimeout(750);
        await frame.locator('img').tap();
        await overlay.locator('img').evaluate(image => image.decode());
        await touch('touchStart', [[1, 300, 400]]);
        await touch('touchMove', [[1, 100, 400]]);
        await touch('touchEnd', []);
        await frame.waitForFunction(() => window.detail.path === 'third');
        assert.equal(await overlay.count(), 1);
        assert.deepEqual(await frame.evaluate(() => window.requests), ['video', 'second', 'denied', 'third']);
        await overlay.locator('img').evaluate(image => image.decode());
        await touch('touchStart', [[1, 100, 400]]);
        await touch('touchMove', [[1, 300, 400]]);
        await touch('touchEnd', []);
        await frame.waitForFunction(() => window.detail.path === 'second');
        assert.equal(await overlay.count(), 1);
        await overlay.locator('img').evaluate(image => image.decode());
        await touch('touchStart', [[1, 100, 400], [2, 300, 400]]);
        await touch('touchMove', [[1, 0, 400], [2, 400, 400]]);
        await touch('touchEnd', []);
        await touch('touchStart', [[1, 100, 400]]);
        await touch('touchMove', [[1, 300, 400]]);
        await touch('touchEnd', []);
        assert.equal(await frame.evaluate(() => window.detail.path), 'second');
        await touch('touchStart', [[1, 100, 400]]);
        await touch('touchMove', [[1, 300, 400]]);
        await touch('touchEnd', []);
        await frame.waitForFunction(() => window.detail.path === 'first');
        assert.equal(await overlay.count(), 1);
        await overlay.tap({position: {x: 200, y: 400}});
        assert.equal(await overlay.count(), 0);
        await frame.locator('img').evaluate(image => image.click());
        assert.equal(await overlay.count(), 1);

        // 验证生产环境带有 commonimage 和 opentype: 'video' 的素材：跳过视频，秒切 commonimage
        await page.evaluate(() => {
            window.dzzoffice.WindowThis.ImageDataParam.data = [
                {dpath: 'img-a', opentype: 'commonimage', ext: 'jpg'},
                {dpath: 'vid-1', opentype: 'video', ext: 'mp4'},
                {dpath: 'vid-2', opentype: 'video', ext: 'mp4'},
                {dpath: 'vid-3', opentype: 'video', ext: 'mp4'},
                {dpath: 'img-b', opentype: 'commonimage', ext: 'png'}
            ];
        });
        await frame.evaluate(() => {
            window.requests = [];
            window.detail.path = 'img-a';
            window.detail.paths = ['img-a'];
        });
        await touch('touchStart', [[1, 300, 400]]);
        await touch('touchMove', [[1, 100, 400]]);
        await touch('touchEnd', []);
        await frame.waitForFunction(() => window.detail.path === 'img-b');
        assert.deepEqual(await frame.evaluate(() => window.requests), ['img-b']);

        await page.locator('iframe').evaluate(element => element.remove());
        await overlay.waitFor({state: 'detached'});
        console.log('PASS Chromium mobile: tap close without controls, inline/fullscreen image-only swipes, denied/video skipping, zoom pan then edge navigation, rotation and cleanup');
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
