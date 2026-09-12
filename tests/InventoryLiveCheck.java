package com.posassist;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit developer-only read-only SQL integration check via Query Kit; not included in jar. */
public final class InventoryLiveCheck {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Pass the absolute Query Kit wrapper path");
        final String helper=args[0];
        EpbInventorySource source=new EpbInventorySource((sql,limit)->query(helper,sql,limit),"1=1","1=1");
        List<Inventory.Store> stores=source.stores();
        System.out.println("roster="+stores.size()+"; destination SA004="+stores.stream().anyMatch(s->s.id.equals("SA004")));
        Inventory.Snapshot mac=source.category(Inventory.Category.MAC);
        List<Inventory.Shortage> macGaps=Inventory.shortages(mac,"SA004");
        if(macGaps.size()<2)throw new Exception("Need two real product samples");
        List<Inventory.Product> products=new ArrayList<Inventory.Product>();
        for(int i=0;i<2;i++){
            String id=macGaps.get(i).product.id;
            List<Inventory.Product> found=source.search(id);
            for(Inventory.Product p:found)if(p.id.equals(id))products.add(p);
        }
        if(products.size()!=2)throw new Exception("Exact search failed to return both known products");
        List<Inventory.Line> lines=Arrays.asList(new Inventory.Line(products.get(0),1),new Inventory.Line(products.get(1),1));
        Inventory.Snapshot stock=source.stock(lines);
        System.out.println("search="+products.size()+"; two-SKU snapshot="+stock.products.size()+"; stores="+stock.stores.size());
        System.out.println("sample1="+products.get(0).id+"; SA004="+stock.qty("SA004",products.get(0).id));
        System.out.println("sample2="+products.get(1).id+"; SA004="+stock.qty("SA004",products.get(1).id));
        for(Inventory.Category cat:Inventory.Category.values()){
            Inventory.Snapshot all=cat==Inventory.Category.MAC?mac:source.category(cat);
            List<Inventory.Shortage> gaps=Inventory.shortages(all,"SA004");
            System.out.println(cat+": products="+all.products.size()+"; low-stock="+gaps.size());
        }
        System.out.println("Live Query Kit check passed (SQL/data only; native UI/session still requires pilot)");
    }
    static List<Map<String,String>> query(String helper,String sql,int limit)throws Exception{
        Path input=Files.createTempFile("posassist-inventory-", ".sql"), errors=Files.createTempFile("posassist-inventory-", ".err");
        try{
            Files.write(input,InventorySql.bounded(sql,limit).getBytes(StandardCharsets.UTF_8));
            Process process=new ProcessBuilder("python3",helper,"--sql-file",input.toString(),"--format","tsv","--timeout","180","--limit",String.valueOf(limit+1)).redirectError(errors.toFile()).start();
            List<Map<String,String>> rows=new ArrayList<Map<String,String>>();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
                String first=reader.readLine();if(first!=null){String[] fields=first.split("\t",-1);String line;
                    while((line=reader.readLine())!=null){String[] values=line.split("\t",-1);if(values.length!=fields.length)throw new Exception("TSV field count mismatch");
                        Map<String,String> row=new LinkedHashMap<String,String>();for(int i=0;i<fields.length;i++)row.put(fields[i],values[i]);rows.add(row);}
                }
            }
            if(process.waitFor()!=0){
                String diagnostic=new String(Files.readAllBytes(errors),StandardCharsets.UTF_8);
                java.util.regex.Matcher match=java.util.regex.Pattern.compile("ORA-[0-9]+[^\\r\\n]*").matcher(diagnostic);
                String code=match.find()?match.group():"no Oracle error code";
                java.util.regex.Matcher kind=java.util.regex.Pattern.compile("[A-Za-z.]+Exception|timed out|Read timed out|Connection refused").matcher(diagnostic);
                while(kind.find())code+="; "+kind.group();
                throw new Exception("Query Kit SQL check failed: "+code);
            }
            if(rows.size()>limit)throw new Exception("Complete result exceeded limit");
            for(Map<String,String> row:rows)
                if(!Integer.toString(rows.size()).equals(row.get("PA_RESULT_ROWS")))throw new Exception("Incomplete query response");
            return rows;
        }finally{Files.deleteIfExists(input);Files.deleteIfExists(errors);}
    }
}
