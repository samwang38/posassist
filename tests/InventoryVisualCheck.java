package com.posassist;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Render the real Swing panel with labelled synthetic fixtures; no EPB connection. */
public final class InventoryVisualCheck {
    static Object get(Object target,String field)throws Exception{Field f=target.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(target);}
    static void set(Object target,String field,Object value)throws Exception{Field f=target.getClass().getDeclaredField(field);f.setAccessible(true);f.set(target,value);}
    static void call(Object target,String name)throws Exception{Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(target);}
    static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container)layout((Container)child);}
    public static void main(String[] args)throws Exception{
        final File dir=new File(args[0]);dir.mkdirs();
        SwingUtilities.invokeAndWait(()->{try{
            InventoryPanel panel=new InventoryPanel("SA004",new EpbInventorySource((sql,limit)->InventoryTest.rosterRows(),"1=1","1=1"));
            JComboBox<Inventory.Store> dest=(JComboBox<Inventory.Store>)get(panel,"destination");
            set(panel,"changing",true);for(Inventory.Store store:InventoryTest.stores())dest.addItem(store);set(panel,"changing",false);
            panel.draft.add(InventoryTest.p("A"),2);panel.draft.add(InventoryTest.p("B"),1);call(panel,"changed");
            set(panel,"stock",InventoryTest.snapshot());set(panel,"mainStock",InventoryTest.snapshot());
            call(get(panel,"productModel"),"refresh");call(panel,"refreshCandidates");call(panel,"stockStatus");call(panel,"refreshShortages");
            JTabbedPane tabs=(JTabbedPane)get(panel,"tabs");
            render(panel,new File(dir,"comparison.png"),1180,760);
            render(panel,new File(dir,"minimum.png"),850,520);
            tabs.setSelectedIndex(1);render(panel,new File(dir,"main-stock.png"),1180,760);
            tabs.setSelectedIndex(0);
            panel.draft.clear();
            java.util.List<Inventory.Product> items=new java.util.ArrayList<Inventory.Product>();
            java.util.List<Inventory.Store> stores=new java.util.ArrayList<Inventory.Store>();
            java.util.Map<String,java.math.BigDecimal> quantities=new java.util.HashMap<String,java.math.BigDecimal>();
            stores.add(new Inventory.Store("SA004","士林門市","1_APR"));
            for(int n=10;n<30;n++)stores.add(new Inventory.Store("SA0"+n,"晶實科技(股)示例"+n+"門市","1_APR"));
            for(int n=0;n<30;n++){
                Inventory.Product p=new Inventory.Product(String.format("07312%03d",n),"iPhone 17 Pro 256GB 示例商品 "+(n+1),"MFHP4TA/A","PCS","3001","4002");
                items.add(p);panel.draft.add(p,1);
                for(Inventory.Store store:stores)quantities.put(Inventory.key(store.id,p.id),java.math.BigDecimal.valueOf(n+2));
            }
            call(panel,"changed");set(panel,"stock",new Inventory.Snapshot(stores,items,quantities,1000));
            call(panel,"refreshCandidates");call(panel,"stockStatus");
            render(panel,new File(dir,"many-products.png"),1180,760);
            JTable matrix=(JTable)get(panel,"comparisonTable");
            JScrollPane pane=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,matrix);
            JPanel canvas=prepare(panel,1180,760);
            pane.getViewport().setViewPosition(new Point(290,440));
            if(pane.getRowHeader().getViewPosition().y!=440)throw new AssertionError("Frozen product rows must follow vertical scroll");
            if(pane.getColumnHeader().getViewPosition().x!=290)throw new AssertionError("Store headings must follow horizontal scroll");
            paint(canvas,new File(dir,"scrolled.png"));
            panel.close();
        }catch(Exception e){throw new RuntimeException(e);}});
        System.out.println("Rendered inventory fixtures to "+dir);
    }
    static JPanel prepare(JComponent content,int width,int height){
        JPanel root=new JPanel(new BorderLayout());root.add(new JLabel("介面驗證 · 示例資料，非即時庫存"),BorderLayout.NORTH);root.add(content,BorderLayout.CENTER);
        root.setSize(width,height);layout(root);layout(root);return root;
    }
    static void render(JComponent content,File file,int width,int height)throws Exception{paint(prepare(content,width,height),file);}
    static void paint(JPanel root,File file)throws Exception{
        BufferedImage image=new BufferedImage(root.getWidth(),root.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,root.getWidth(),root.getHeight());root.printAll(g);g.dispose();ImageIO.write(image,"png",file);
    }
}
