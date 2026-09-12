package com.posassist;

import java.util.*;

/** Read-only replay of the user's search terms. Uses Query Kit in development only. */
public final class InventorySearchLiveCheck {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Pass Query Kit wrapper path");
        EpbInventorySource source=new EpbInventorySource((sql,limit)->InventoryLiveCheck.query(args[0],sql,limit),"1=1","1=1");
        for(String query:Arrays.asList("ipad air 256","iphone 17 pro 256")){
            long start=System.currentTimeMillis();List<Inventory.Product> products=source.search(query);
            if(products.isEmpty())throw new AssertionError("No products for "+query);
            Set<String> ids=new HashSet<String>();
            for(Inventory.Product p:products){
                if(!ids.add(p.id))throw new AssertionError("Duplicate product");
                String text=(p.id+" "+p.name+" "+p.model).toUpperCase(Locale.ROOT);
                for(String term:InventorySql.terms(query))if(!text.contains(term))throw new AssertionError("Missing search term");
            }
            if(query.startsWith("iphone")&&!ids.containsAll(Arrays.asList("07312334","07312335","07312336")))
                throw new AssertionError("Recorded iPhone color variants missing");
            System.out.println(query+": "+products.size()+" products; "+((System.currentTimeMillis()-start)/1000.0)+" seconds");
        }
        System.out.println("Recorded search terms: passed (normal sale items only)");
    }
}
