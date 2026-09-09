const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../web/dzz/pichome/template/librarylist/pc/components/index/image.htm'), 'utf8');
const hooks = source.slice(source.lastIndexOf('\t\tmounted(){'), source.lastIndexOf('\n\t};'));
const listeners = new Map();
const document = {
    addEventListener(name, handler, capture) { assert.equal(capture, true); listeners.set(name, handler); },
    removeEventListener(name, handler, capture) { assert.equal(capture, true); assert.equal(listeners.get(name), handler); listeners.delete(name); }
};
const component = vm.runInNewContext('({' + hooks + '})', {document});
const instance = {ispc: false};
component.mounted.call(instance);
assert.deepEqual([...listeners.keys()], ['contextmenu']);
function context(pointerType, card) {
    const event = {pointerType, target: {closest: () => card}, preventDefault() { this.prevented = true; }, stopImmediatePropagation() { this.stopped = true; }};
    listeners.get('contextmenu')(event);
    return event;
}
assert(context('touch', true).prevented);
assert(context(undefined, true).stopped);
assert(!context('touch', false).prevented);
instance.ispc = true;
assert(!context('mouse', true).prevented);
assert(context('touch', true).prevented);
component.beforeUnmount.call(instance);
assert.equal(listeners.size, 0);
assert(!source.includes('clearLongPressTimer'));
assert(!source.includes('__pichomeLongPressed'));
assert.equal(source.split(':trigger="ispc ? \'hover\' : \'click\'"').length - 1, 2);
console.log('PASS: no long-press timer, mobile native menu blocked, desktop right-click preserved, both ellipsis menus use mobile click, cleanup');
