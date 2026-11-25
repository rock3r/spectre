import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import Meta from 'gi://Meta';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';

const XML = `<node><interface name="dev.sebastiano.Spectre.MutterControlVerification">
  <method name="LookupOwnedX11Window"><arg type="t" direction="in" name="xid"/>
  <arg type="t" direction="out" name="window_id"/></method>
</interface></node>`;
const BUS = 'dev.sebastiano.Spectre.MutterControlVerification';
const PATH = '/dev/sebastiano/Spectre/MutterControlVerification';
const ERROR = `${BUS}.Error`;

export default class MutterControlVerification extends Extension {
    enable() {
        const service = {
            LookupOwnedX11WindowAsync: async ([xid], invocation) => {
                try {
                    const sender = invocation.get_sender();
                    const reply = await Gio.DBus.session.call(
                        'org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
                        'GetConnectionUnixProcessID', new GLib.Variant('(s)', [sender]),
                        new GLib.VariantType('(u)'), Gio.DBusCallFlags.NONE, 3000, null);
                    const callerPid = reply.deepUnpack()[0];
                    const matches = global.get_window_actors().map(a => a.meta_window).filter(w => {
                        if (w.get_pid() !== callerPid ||
                            w.get_client_type() !== Meta.WindowClientType.X11)
                            return false;
                        // GNOME 46 src/core/window.c produces the complete description as 0x%lx.
                        const description = w.get_description();
                        if (!/^0x[0-9a-fA-F]+$/.test(description))
                            return false;
                        return BigInt(description) === BigInt(xid);
                    });
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

    disable() {
        if (this._name)
            Gio.bus_unown_name(this._name);
        this._name = 0;
        this._object?.unexport();
        this._object = null;
    }
}
