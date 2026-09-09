const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../web/dzz/xgplayer/template/main.htm'), 'utf8');
const begin = source.indexOf('function pichomePauseOuterPreviews()');
const end = source.indexOf('pichomePauseOuterPreviews();', begin) + 'pichomePauseOuterPreviews();'.length;
function frame() { const target = new EventTarget(); target.CustomEvent = Event; target.parent = target; target.calls = 0; target.addEventListener('pichome-detail-open', () => target.calls++); return target; }
const top = frame(), detail = frame(), player = frame();
player.parent = detail; detail.parent = top;
vm.runInNewContext(source.slice(begin, end), {window: player});
assert.equal(top.calls, 1); assert.equal(detail.calls, 1); assert.equal(player.calls, 0);
const tab = frame(); tab.opener = top; top.opener = tab;
vm.runInNewContext(source.slice(begin, end), {window: tab});
assert.equal(top.calls, 2);
const foreign = frame(); foreign.parent = {get CustomEvent() { throw Error('Cross origin'); }};
vm.runInNewContext(source.slice(begin, end), {window: foreign});
console.log('PASS: nested detail frames and separate tabs pause outer previews, opener cycles and cross-origin boundaries are safe');
