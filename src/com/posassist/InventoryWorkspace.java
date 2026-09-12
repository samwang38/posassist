package com.posassist;

import java.awt.*;

/** Login owns only native buttons. The inventory window is created on explicit click. */
final class InventoryWorkspace {
    private final String identity;
    private final NativeInventoryBridge nativeBridge;
    private InventoryPanel inventory;
    private boolean closed;

    InventoryWorkspace(String identity) {
        this.identity=identity;
        nativeBridge=new NativeInventoryBridge(ids->{
            if(closed||!matches(currentIdentity()))return;
            if(inventory==null){
                String store=Home.value("config/posassist.properties","inventoryDefaultStore","SA004");
                if(!store.matches("SA[0-9]{3}")||store.equals("SA999"))store="SA004";
                Object owner=Safe.staticCall("com.ipt.epbfrw.EpbSharedObjects","getShellFrame",new Class<?>[0],new Object[0]);
                inventory=new InventoryPanel(store,null,owner instanceof Window?(Window)owner:null);
            }
            inventory.importNative(ids);
        },message->PosLog.warn(message));
    }
    static String currentIdentity(){
        try{return EpbInventorySource.shared("getUserId").isEmpty()?"":EpbInventorySource.identity();}
        catch(Exception e){return "";}
    }
    boolean matches(String identity){return this.identity.equals(identity);}
    void nativeApplicationEvent(String event,Object app){
        if(closed||!matches(currentIdentity()))return;
        if("applicationClosed".equals(event))nativeBridge.detach(app);
        else if("applicationOpened".equals(event)||"applicationActivated".equals(event))nativeBridge.attach(app);
    }
    boolean allowsPosFocus(){
        Window active=KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        while(active!=null){if("PosAssist-InventoryComparison".equals(active.getName()))return false;active=active.getOwner();}
        return true;
    }
    void close(){closed=true;nativeBridge.close();if(inventory!=null)inventory.close();inventory=null;}
}
