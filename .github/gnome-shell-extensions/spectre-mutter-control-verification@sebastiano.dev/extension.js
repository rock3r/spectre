import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import Meta from 'gi://Meta';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';

const XML = `<node><interface name="dev.sebastiano.Spectre.MutterControlVerification">
  <method name="LookupOwnedX11Window"><arg type="t" direction="in" name="xid"/>
  <arg type="t" direction="out" name="window_id"/></method>
  <method name="InspectDescendantX11Window"><arg type="t" direction="in" name="xid"/>
  <arg type="u" direction="in" name="pid"/><arg type="t" direction="out" name="window_id"/>
  <arg type="s" direction="out" name="geometry_json"/></method>
  <method name="GetStageDiagnostics"><arg type="s" direction="out" name="diagnostics_json"/></method>
</interface></node>`;
const BUS = 'dev.sebastiano.Spectre.MutterControlVerification';
const PATH = '/dev/sebastiano/Spectre/MutterControlVerification';
const ERROR = `${BUS}.Error`;

export default class MutterControlVerification extends Extension {
    enable() {
        const service = {
            GetStageDiagnostics: () => {
                const monitors = Main.layoutManager.monitors.map(m => ({x: m.x, y: m.y,
                    width: m.width, height: m.height, scale: m.geometry_scale ?? null}));
                return [JSON.stringify({units: 'Mutter logical stage pixels', width: global.stage.width,
                    height: global.stage.height, monitors})];
            },
            InspectDescendantX11WindowAsync: async ([xid, pid], invocation) => {
                try {
                    const callerPid = await this._senderPid(invocation.get_sender());
                    if (!this._isDescendant(pid, callerPid))
                        throw new Error(`target PID ${pid} is not the caller or its descendant`);
                    const matches = this._ownedMatches(xid, pid);
                    if (matches.length !== 1)
                        throw new Error(`expected one PID/XID match; found ${matches.length}`);
                    const w = matches[0];
                    const tuple = rect => ({x: rect.x, y: rect.y, width: rect.width, height: rect.height});
                    const result = {units: 'Mutter logical stage pixels', frameRect: tuple(w.get_frame_rect()),
                        bufferRect: tuple(w.get_buffer_rect()), clientContentRect: null,
                        clientContentRectExported: typeof w.get_client_content_rect === 'function'};
                    if (result.clientContentRectExported)
                        result.clientContentRect = tuple(w.get_client_content_rect());
                    invocation.return_value(new GLib.Variant('(ts)', [BigInt(w.get_id()), JSON.stringify(result)]));
                } catch (error) {
                    invocation.return_dbus_error(`${ERROR}.Denied`, `${error}`);
                }
            },
            LookupOwnedX11WindowAsync: async ([xid], invocation) => {
                try {
                    const callerPid = await this._senderPid(invocation.get_sender());
                    const matches = this._ownedMatches(xid, callerPid);
                    if (matches.length !== 1) {
                        invocation.return_dbus_error(`${ERROR}.NotFound`,
                            `expected exactly one caller-owned X11 window for XID ${xid}; found ${matches.length}`);
                        return;
                    }
                    invocation.return_value(new GLib.Variant('(t)', [BigInt(matches[0].get_id())]));
                } catch (error) {
                    invocation.return_dbus_error(`${ERROR}.Failed`, `${error}`);
                }
            },
        };
        this._object = Gio.DBusExportedObject.wrapJSObject(XML, service);
        this._object.export(Gio.DBus.session, PATH);
        this._name = Gio.bus_own_name_on_connection(
            Gio.DBus.session, BUS, Gio.BusNameOwnerFlags.NONE, null, null);
    }

    async _senderPid(sender) {
        const reply = await Gio.DBus.session.call(
            'org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
            'GetConnectionUnixProcessID', new GLib.Variant('(s)', [sender]),
            new GLib.VariantType('(u)'), Gio.DBusCallFlags.NONE, 3000, null);
        return reply.deepUnpack()[0];
    }

    _ownedMatches(xid, pid) {
        return global.get_window_actors().map(a => a.meta_window).filter(w => {
            if (w.get_pid() !== pid || w.get_client_type() !== Meta.WindowClientType.X11)
                return false;
            const description = w.get_description();
            return /^0x[0-9a-fA-F]+$/.test(description) && BigInt(description) === BigInt(xid);
        });
    }

    _isDescendant(pid, ancestor) {
        for (let current = pid, depth = 0; current > 1 && depth < 64; depth++) {
            if (current === ancestor)
                return true;
            try {
                const [, bytes] = Gio.File.new_for_path(`/proc/${current}/stat`).load_contents(null);
                const stat = new TextDecoder().decode(bytes);
                current = Number(stat.slice(stat.lastIndexOf(')') + 2).split(' ')[1]);
            } catch (_) {
                return false;
            }
        }
        return false;
    }

    disable() {
        if (this._name)
            Gio.bus_unown_name(this._name);
        this._name = 0;
        this._object?.unexport();
        this._object = null;
    }
}
