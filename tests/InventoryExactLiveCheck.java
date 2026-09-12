package com.posassist;
import java.util.*;
/** Developer-only Query Kit comparison of the exact native selected codes. */
public final class InventoryExactLiveCheck {
 public static void main(String[] args)throws Exception {
  EpbInventorySource source=new EpbInventorySource((sql,limit)->InventoryLiveCheck.query(args[0],sql,limit),"1=1","1=1");
  List<String> ids=Arrays.asList("09600040","50300018","09600039","50300019","09600041","50300020");
  try { source.products(ids); throw new AssertionError("Demo batch unexpectedly accepted"); }
  catch(Exception e){if(!e.getMessage().contains("09600039"))throw e;System.out.println("Demo batch correctly blocked: 09600039");}
  ids=new ArrayList<String>(ids);ids.remove("09600039");
  List<Inventory.Product> products=source.products(ids);List<Inventory.Line> lines=new ArrayList<Inventory.Line>();
  for(Inventory.Product p:products)lines.add(new Inventory.Line(p,1));
  Inventory.Snapshot snapshot=source.stock(lines);
  System.out.println("Exact lookup="+products.size()+"; complete stores="+snapshot.stores.size());
  for(Inventory.Line line:lines)System.out.println(line.product.id+" SA004="+snapshot.qty("SA004",line.product.id));
  System.out.println("Common suppliers="+Inventory.candidates(snapshot,lines,"SA004",true).size());
  System.out.println("Query Kit exact selection check passed (service-visible scope)");
 }
}
