#!/usr/bin/python3
"""Real two-window GTK fixture with a deliberately cacheless AT-SPI adapter."""
import argparse, json, os, time
from pathlib import Path
import dbus, dbus.mainloop.glib, dbus.service, gi
gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk, GdkX11

A11Y="org.a11y.atspi"; ACC=A11Y+".Accessible"; COMP=A11Y+".Component"
PROP="org.freedesktop.DBus.Properties"; APP=A11Y+".Application"; NULL=("", "/org/a11y/atspi/null")

class Journal:
    def __init__(self, path, bus):
        self.f=open(path,"a",buffering=1); self.admin=dbus.Interface(bus.get_object("org.freedesktop.DBus","/org/freedesktop/DBus"),"org.freedesktop.DBus")
    def put(self,sender,path,interface,member,outcome="return",detail=None):
        try: pid=int(self.admin.GetConnectionUnixProcessID(sender))
        except dbus.DBusException: pid=None
        self.f.write(json.dumps({"monotonicNs":time.monotonic_ns(),"sender":sender,"senderPid":pid,"path":path,"interface":interface,"member":member,"outcome":outcome,"detail":detail})+"\n"); os.fsync(self.f.fileno())

class Node(dbus.service.Object):
    def __init__(self,bus,j,path,role,name,ident,parent=NULL,bounds=(0,0,10,10),fault=None):
        super().__init__(bus,path); self.j=j; self.path=path; self.role=role; self.name=name; self.ident=ident; self.parent=parent; self.bounds=bounds; self.fault=fault; self.children=[]; self.application=NULL
    def note(self,s,i,m): self.j.put(s,self.path,i,m)
    def fail(self,s,i,m):
        if self.fault=="error":
            detail="fixture ordinary child read rejected before deadline"; self.j.put(s,self.path,i,m,A11Y+".Error.NotAvailable",detail); raise dbus.DBusException(detail,name=A11Y+".Error.NotAvailable")
        if self.fault=="slow": time.sleep(1.2)
    @dbus.service.method(ACC,in_signature="",out_signature="a(so)",sender_keyword="sender")
    def GetChildren(self,sender=None): self.note(sender,ACC,"GetChildren"); self.fail(sender,ACC,"GetChildren"); return self.children
    @dbus.service.method(ACC,in_signature="",out_signature="a{ss}",sender_keyword="sender")
    def GetAttributes(self,sender=None): self.note(sender,ACC,"GetAttributes"); self.fail(sender,ACC,"GetAttributes"); return {"id":self.ident,"fixture":"qa-atspi-multi-window-contract"}
    @dbus.service.method(ACC,in_signature="",out_signature="u",sender_keyword="sender")
    def GetRole(self,sender=None): self.note(sender,ACC,"GetRole"); self.fail(sender,ACC,"GetRole"); return dbus.UInt32(self.role)
    @dbus.service.method(ACC,in_signature="",out_signature="s",sender_keyword="sender")
    def GetRoleName(self,sender=None): self.note(sender,ACC,"GetRoleName"); self.fail(sender,ACC,"GetRoleName"); return {14:"application",68:"frame",97:"grouping",43:"label",42:"push button"}.get(self.role,"unknown")
    @dbus.service.method(ACC,in_signature="",out_signature="s",sender_keyword="sender")
    def GetLocalizedRoleName(self,sender=None): return self.GetRoleName(sender)
    @dbus.service.method(ACC,in_signature="",out_signature="au",sender_keyword="sender")
    def GetState(self,sender=None): self.note(sender,ACC,"GetState"); self.fail(sender,ACC,"GetState"); return [dbus.UInt32(0),dbus.UInt32(0)]
    @dbus.service.method(ACC,in_signature="",out_signature="(so)",sender_keyword="sender")
    def GetApplication(self,sender=None): self.note(sender,ACC,"GetApplication"); return self.application
    @dbus.service.method(ACC,in_signature="",out_signature="as",sender_keyword="sender")
    def GetInterfaces(self,sender=None): self.note(sender,ACC,"GetInterfaces"); return [ACC,COMP]
    @dbus.service.method(COMP,in_signature="u",out_signature="(iiii)",sender_keyword="sender")
    def GetExtents(self,coord,sender=None): self.note(sender,COMP,"GetExtents"); self.fail(sender,COMP,"GetExtents"); return tuple(dbus.Int32(x) for x in self.bounds)
    @dbus.service.method(PROP,in_signature="ss",out_signature="v",sender_keyword="sender")
    def Get(self,interface,name,sender=None):
        self.note(sender,PROP,"Get"); self.fail(sender,PROP,"Get")
        values={"Name":self.name,"Parent":self.parent,"ChildCount":dbus.Int32(len(self.children)),"AccessibleId":self.ident}
        if name in values:return values[name]
        raise dbus.DBusException("unknown property",name="org.freedesktop.DBus.Error.UnknownProperty")

def main():
    p=argparse.ArgumentParser(); p.add_argument("--ready",type=Path,required=True); p.add_argument("--journal",type=Path,required=True); p.add_argument("--fault",choices=("none","error","slow"),default="none"); a=p.parse_args()
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True); session=dbus.SessionBus(); address=str(session.get_object("org.a11y.Bus","/org/a11y/bus").GetAddress(dbus_interface="org.a11y.Bus")); bus=dbus.bus.BusConnection(address); owner=bus.get_unique_name(); j=Journal(a.journal,bus)
    windows=[]
    for title,marker,x in (("Alpha","alpha-marker",40),("Beta","beta-marker",420)):
        w=Gtk.Window(title=title); box=Gtk.Box(orientation=Gtk.Orientation.VERTICAL); box.add(Gtk.Label(label=title+" distinct label")); box.add(Gtk.Button(label=marker)); w.add(box); w.set_default_size(320,180); w.move(x,60); w.show_all(); windows.append(w)
    while Gtk.events_pending(): Gtk.main_iteration()
    app=Node(bus,j,"/org/a11y/atspi/accessible/app",14,"QA two-window application","qa-app")
    objects=[app]; paths={}
    for index,(title,marker,x) in enumerate((("Alpha","alpha-marker",40),("Beta","beta-marker",420))):
        base=f"/org/a11y/atspi/accessible/{title.lower()}"; root=Node(bus,j,base,68,title,title.lower()+"-root",(owner,app.path),(x,60,320,180)); group=Node(bus,j,base+"/group",97,title+" group",title.lower()+"-group",(owner,root.path),(x+10,90,280,110)); interior=Node(bus,j,base+"/group/interior",97,title+" interior",title.lower()+"-interior",(owner,group.path),(x+20,110,240,70)); mark=Node(bus,j,base+"/group/interior/marker",42,marker,marker,(owner,interior.path),(x+30,130,120,30),a.fault if title=="Alpha" else None)
        root.children=[(owner,group.path)]; group.children=[(owner,interior.path)]; interior.children=[(owner,mark.path)]; app.children.append((owner,root.path)); objects += [root,group,interior,mark]; paths[title.lower()]=root.path
    for o in objects:o.application=(owner,app.path)
    dbus.Interface(bus.get_object("org.a11y.atspi.Registry","/org/a11y/atspi/accessible/root"),A11Y+".Socket").Embed((owner,app.path))
    xids=[]
    for w in windows:
        g=w.get_window(); xids.append(int(GdkX11.X11Window.get_xid(g)))
    a.ready.write_text(json.dumps({"pid":os.getpid(),"owner":owner,"application":app.path,"windows":paths,"xids":xids,"x11":[{"xid":x,"ownerPid":os.getpid()} for x in xids],"fault":a.fault})+"\n")
    GLib.MainLoop().run()
if __name__=="__main__":main()
