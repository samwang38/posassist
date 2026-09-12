package com.posassist;

import java.awt.Container;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.*;

/** One button per native application instance, owned by a single login workspace. EDT only. */
final class NativeInventoryBridge implements AutoCloseable {
    interface Access {
        Object view(Object app) throws Exception;
        Object criteria(Object view) throws Exception;
        void install(Object view,JButton button) throws Exception;
    }
    static final class ReflectionAccess implements Access {
        public Object view(Object app)throws Exception {
            Method method=app.getClass().getMethod("getApplicationView");method.setAccessible(true);return method.invoke(app);
        }
        public Object criteria(Object view)throws Exception {return invoke("getCurrentCriteriaItems",new Class<?>[]{Class.forName("com.epb.framework.View")},view);}
        public void install(Object view,JButton button)throws Exception {invoke("installCriteriaComponent",new Class<?>[]{Class.forName("com.epb.framework.View"),JComponent.class},view,button);}
        private Object invoke(String name,Class<?>[] signature,Object...args)throws Exception {
            Method method=Class.forName("com.epb.framework.EnquiryViewBuilder").getMethod(name,signature);
            // Inherited APIs are declared on package-private CommonViewBuilder in EPB 9.53.
            method.setAccessible(true);return method.invoke(null,args);
        }
    }
    private final Access access;
    private final Consumer<List<String>> selected;
    private final Consumer<String> failure;
    private final Map<Object,JButton> buttons=new IdentityHashMap<Object,JButton>();
    private boolean closed;
    NativeInventoryBridge(Consumer<List<String>> selected,Consumer<String> failure){this(selected,new ReflectionAccess(),failure);}
    NativeInventoryBridge(Consumer<List<String>> selected,Access access){this(selected,access,message->{});}
    NativeInventoryBridge(Consumer<List<String>> selected,Access access,Consumer<String> failure){this.selected=selected;this.access=access;this.failure=failure;}
    void attach(Object app) {
        if(closed||buttons.containsKey(app))return;
        JButton button=new JButton("庫存工具");button.setFocusable(false);
        button.setToolTipText("依原生目前勾選開啟庫存比較；不必先執行原生庫存查詢");
        try {
            final Object view=access.view(app);
            if(!(view instanceof JComponent))throw new Exception("原生畫面不相容");
            button.addActionListener(e->{
                if(closed||buttons.get(app)!=button)return;
                try { selected.accept(NativeInventorySelection.read(access.criteria(view))); }
                catch(Exception | LinkageError error){
                    String message=error instanceof ReflectiveOperationException ? "原生商品讀取介面不相容，請停止帶入並執行自我檢查" : error.getMessage();
                    JOptionPane.showMessageDialog((JComponent)view,message,"共同供貨店比較",JOptionPane.WARNING_MESSAGE);
                }
            });
            access.install(view,button);buttons.put(app,button);
        } catch(Exception | LinkageError error) {
            remove(button);PosLog.warn("STORESUM 比較入口掛載失敗；保留原生功能");
            failure.accept("STORESUM 比較入口掛載失敗，請停止帶入並執行自我檢查；原生功能仍可使用");
        }
    }
    void detach(Object app){JButton button=buttons.remove(app);if(button!=null)remove(button);}
    private static void remove(JButton button){
        button.setEnabled(false);for(java.awt.event.ActionListener listener:button.getActionListeners())button.removeActionListener(listener);
        Container parent=button.getParent();if(parent!=null){parent.remove(button);parent.revalidate();parent.repaint();}
    }
    public void close(){closed=true;for(JButton button:buttons.values())remove(button);buttons.clear();}
}
