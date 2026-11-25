import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';

const XML = `<node><interface name="dev.sebastiano.Spectre.WindowIdentity">
  <method name="LookupWindow"><arg type="u" direction="in" name="owner_pid"/>
  <arg type="t" direction="in" name="xid"/><arg type="u" direction="out" name="window_id"/></method>
</interface></node>`;
const BUS = 'dev.sebastiano.Spectre.WindowIdentity';
const PATH = '/dev/sebastiano/Spectre/WindowIdentity';

function parentPid(pid) {
    try {
        const [, bytes] = GLib.file_get_contents(`/proc/${pid}/status`);
        const match = new TextDecoder().decode(bytes).match(/^PPid:\s+(\d+)$/m);
        return match ? Number(match[1]) : 0;
    } catch (_) { return 0; }
}

function isAncestor(ancestor, child) {
    for (let pid = child, depth = 0; pid > 1 && depth < 32; depth++) {
        if (pid === ancestor) return true;
        pid = parentPid(pid);
    }
    return false;
}

export default class SpectreWindowIdentity extends Extension {
    enable() {
        const service = {
            LookupWindowAsync: async ([ownerPid, xid], invocation) => {
                const sender = invocation.get_sender();
                const reply = await Gio.DBus.session.call(
                    'org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
                    'GetConnectionUnixProcessID', new GLib.Variant('(s)', [sender]),
                    new GLib.VariantType('(u)'), Gio.DBusCallFlags.NONE, 3000, null);
                const callerPid = reply.deepUnpack()[0];
                if (!isAncestor(ownerPid, callerPid))
                    throw new Error(`PID ${ownerPid} is not the caller or its ancestor`);
                const matches = global.get_window_actors().map(a => a.meta_window).filter(w =>
                    w.get_pid() === ownerPid && BigInt(w.get_xwindow()) === BigInt(xid));
                if (matches.length !== 1)
                    throw new Error(`expected exactly one owned window for PID ${ownerPid}, XID ${xid}; found ${matches.length}`);
                invocation.return_value(new GLib.Variant('(u)', [matches[0].get_id()]));
            },
        };
        this._object = Gio.DBusExportedObject.wrapJSObject(XML, service);
        this._object.export(Gio.DBus.session, PATH);
        this._name = Gio.bus_own_name_on_connection(Gio.DBus.session, BUS, Gio.BusNameOwnerFlags.NONE, null, null);
    }
    disable() {
        if (this._name) Gio.bus_unown_name(this._name);
        this._name = 0;
        this._object?.unexport();
        this._object = null;
    }
}
