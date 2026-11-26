/* exported SpectreWindowIdentityCore */
const X11_DESCRIPTION = /^0x([0-9a-fA-F]+)(?: \([\s\S]*\))?$/;

class OneTimeBindings {
    constructor(now = Date.now, ttlMs = 30_000, maximum = 256) {
        this.values = new Map();
        this.now = now;
        this.ttlMs = ttlMs;
        this.maximum = maximum;
    }

    cleanup() {
        const now = this.now();
        for (const [key, value] of this.values) {
            if (value.expiresAt <= now) this.values.delete(key);
        }
    }

    put(token, ownerPid, windowId) {
        this.cleanup();
        if (this.values.size >= this.maximum) throw new Error('too many pending window bindings');
        this.values.set(token, {ownerPid, windowId, expiresAt: this.now() + this.ttlMs});
    }

    redeem(token, ownerPid) {
        this.cleanup();
        const value = this.values.get(token);
        this.values.delete(token);
        return value?.ownerPid === ownerPid ? value.windowId : null;
    }

    clear() {
        this.values.clear();
    }
}

function parseX11WindowDescription(description) {
    if (typeof description !== 'string') return null;
    const match = description.match(X11_DESCRIPTION);
    return match === null ? null : BigInt(`0x${match[1]}`);
}

function windowMatchesXid(window, ownerPid, xid, x11ClientType) {
    if (window.get_client_type() !== x11ClientType || window.get_pid() !== ownerPid) return false;
    const windowXid = parseX11WindowDescription(window.get_description());
    return windowXid !== null && windowXid === BigInt(xid);
}

var SpectreWindowIdentityCore = {
    parseX11WindowDescription,
    windowMatchesXid,
    OneTimeBindings,
};

globalThis.SpectreWindowIdentityCore = SpectreWindowIdentityCore;
