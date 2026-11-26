import assert from 'node:assert/strict';
import {
    OneTimeBindings,
    parseX11WindowDescription,
    windowMatchesXid,
} from './spectre-window-identity@sebastiano.dev/windowIdentity.js';

const X11 = 1;
const WAYLAND = 0;
const window = ({clientType = X11, description = '0x2a', pid = 1234} = {}) => ({
    get_client_type: () => clientType,
    get_description: () => description,
    get_pid: () => pid,
});

assert.equal(parseX11WindowDescription('0x2a'), 42n);
assert.equal(parseX11WindowDescription('0x2a (title)'), 42n);
for (const value of ['2a', 'prefix 0x2a', '0x2a title', '0x2a (title) trailing', '0x2aZZ']) {
    assert.equal(parseX11WindowDescription(value), null, value);
}
assert(windowMatchesXid(window(), 1234, 42n, X11));
assert(!windowMatchesXid(window({clientType: WAYLAND}), 1234, 42n, X11));
assert(!windowMatchesXid(window({pid: 7}), 1234, 42n, X11));

let now = 100;
const bindings = new OneTimeBindings(() => now, 10, 2);
bindings.put('good', 1234, 2n);
assert.equal(bindings.redeem('good', 7), null);
assert.equal(bindings.redeem('good', 1234), null);
bindings.put('once', 1234, 2n);
assert.equal(bindings.redeem('once', 1234), 2n);
assert.equal(bindings.redeem('once', 1234), null);
bindings.put('expires', 1234, 3n);
now = 111;
assert.equal(bindings.redeem('expires', 1234), null);
bindings.put('a', 1, 1n);
bindings.put('b', 1, 1n);
assert.throws(() => bindings.put('c', 1, 1n), /too many/);
