const {Gio, GLib, Meta} = imports.gi;
const ExtensionUtils = imports.misc.extensionUtils;
const {SpectreWindowIdentityCore} = ExtensionUtils.getCurrentExtension().imports.windowIdentityCore;

const XML = `<node><interface name="dev.sebastiano.Spectre.WindowIdentity">
  <method name="LookupWindow"><arg type="u" direction="in" name="owner_pid"/><arg type="t" direction="in" name="xid"/><arg type="s" direction="out" name="binding_token"/></method>
  <method name="RedeemWindow"><arg type="s" direction="in" name="binding_token"/><arg type="u" direction="in" name="owner_pid"/><arg type="t" direction="out" name="window_id"/></method>
</interface></node>`;
const BUS = 'dev.sebastiano.Spectre.WindowIdentity';
const PATH = '/dev/sebastiano/Spectre/WindowIdentity';
const ERROR = `${BUS}.Error`;

function parentPid(pid) {
    try {
        const [, bytes] = GLib.file_get_contents(`/proc/${pid}/status`);
        const match = imports.byteArray.toString(bytes).match(/^PPid:\s+(\d+)$/m);
        return match ? Number(match[1]) : 0;
    } catch (_) {
        return 0;
    }
}

function isAncestor(ancestor, child) {
    for (let pid = child, depth = 0; pid > 1 && depth < 32; depth++) {
        if (pid === ancestor) return true;
        pid = parentPid(pid);
    }
    return false;
}

async function senderPid(invocation) {
    const reply = await Gio.DBus.session.call(
        'org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
        'GetConnectionUnixProcessID', new GLib.Variant('(s)', [invocation.get_sender()]),
        new GLib.VariantType('(u)'), Gio.DBusCallFlags.NONE, 3000, null);
    return reply.deepUnpack()[0];
}

class SpectreWindowIdentity {
    enable() {
        this._bindings = new SpectreWindowIdentityCore.OneTimeBindings();
        const service = {
            LookupWindowAsync: async ([ownerPid, xid], invocation) => {
                try {
                    const callerPid = await senderPid(invocation);
                    if (!isAncestor(ownerPid, callerPid)) {
                        invocation.return_dbus_error(`${ERROR}.AccessDenied`, `PID ${ownerPid} is not the caller or its ancestor`);
                        return;
                    }
                    const matches = global.get_window_actors().map(a => a.meta_window).filter(w =>
        SpectreWindowIdentityCore.windowMatchesXid(w, ownerPid, xid, Meta.WindowClientType.X11));
                    if (matches.length !== 1) {
                        invocation.return_dbus_error(`${ERROR}.NotFound`, `expected exactly one owned window for PID ${ownerPid}, XID ${xid}; found ${matches.length}`);
                        return;
                    }
                    const token = GLib.uuid_string_random();
                    this._bindings.put(token, ownerPid, matches[0].get_id());
                    invocation.return_value(new GLib.Variant('(s)', [token]));
                } catch (error) {
                    invocation.return_dbus_error(`${ERROR}.Failed`, `${error}`);
                }
            },
            RedeemWindowAsync: ([token, ownerPid], invocation) => {
                const windowId = this._bindings.redeem(token, ownerPid);
                if (windowId === null) {
                    invocation.return_dbus_error(`${ERROR}.AccessDenied`, 'unknown, expired, replayed, or mismatched window binding');
                    return;
                }
                invocation.return_value(new GLib.Variant('(t)', [windowId]));
            },
        };
        this._object = Gio.DBusExportedObject.wrapJSObject(XML, service);
        this._object.export(Gio.DBus.session, PATH);
        this._name = Gio.bus_own_name_on_connection(Gio.DBus.session, BUS, Gio.BusNameOwnerFlags.NONE, null, null);
    }

    disable() {
        if (this._name) Gio.bus_unown_name(this._name);
        this._name = 0;
        if (this._object) this._object.unexport();
        this._object = null;
        if (this._bindings) this._bindings.clear();
    }
}

function init() {
    return new SpectreWindowIdentity();
}
