const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const template = fs.readFileSync(path.join(__dirname, '../web/dzz/xgplayer/template/main.htm'), 'utf8');
const start = template.slice(template.indexOf('var pichomeIsVideoLoop'), template.indexOf('if (window.PichomeCache'));
const initial = start.slice(0, start.lastIndexOf('try {'));
const ended = template.slice(template.lastIndexOf('<script type="text/javascript">') + '<script type="text/javascript">'.length, template.lastIndexOf('</script>'));
for (const mobile of [false, true]) {
    for (const setting of [null, '0', '1', 'blocked']) {
        let handler;
        let plays = 0;
        const storage = {getItem() { if (setting === 'blocked') throw Error('Storage blocked'); return setting; }};
        const window = {localStorage: storage};
        window.parent = window.top = window;
        const player = {currentTime: 10, on(name, callback) { assert.equal(name, 'ended'); handler = callback; }, play() { plays++; return Promise.resolve(); }};
        const context = vm.createContext({window, player, pichomeMobileVideoLoop: mobile});
        vm.runInContext(initial, context);
        assert.equal(context.pichomeIsVideoLoop, mobile || setting === '1');
        vm.runInContext(ended, context);
        assert.equal(plays, 0);
        handler();
        handler();
        assert.equal(plays, mobile || setting === '1' ? 2 : 0);
        assert.equal(player.currentTime, mobile || setting === '1' ? 0 : 10);
    }
}
console.log('PASS: mobile repeat playback, storage failures, desktop loop preference, no automatic play before ended');
