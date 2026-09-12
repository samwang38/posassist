package com.posassist;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public final class SelfTestConfigTest {
 static int checks;
 static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 static boolean valid(String config,Set<String> fields){return !SelfTest.vipFieldChecks(config,fields).containsValue(false);}
 public static void main(String[] args)throws Exception {
  Set<String> defaults=new LinkedHashSet<String>(Arrays.asList("name","vipPhone1","emailAddr","birthDate","gender"));
  check(valid("",defaults),"defaults pass");defaults.remove("gender");check(!valid("",defaults),"missing default still detected");
  String old=System.getProperty("posassist.logDir");Path root=Files.createTempDirectory("posassist-field-check-");
  try {
   Files.createDirectories(root.resolve("config"));System.setProperty("posassist.logDir",root.resolve("logs").toString());
   String four="name,vipPhone1,emailAddr,birthDate";
   Path config=root.resolve("config/posassist.properties");byte[] bytes=("vipCreateFields="+four+"\n").getBytes(StandardCharsets.UTF_8);
   Files.write(config,bytes);Set<String> actual=VipCreator.visibleFields();
   check(valid(four,actual)&&!actual.contains("gender"),"real four-field config does not require gender");
   check(Arrays.equals(bytes,Files.readAllBytes(config)),"diagnosis preserves store settings");
   String seven=four+",vipPhone2,cardNo,address1";Files.write(config,("vipCreateFields="+seven+"\n").getBytes(StandardCharsets.UTF_8));
   check(valid(seven,VipCreator.visibleFields()),"custom seven fields allowed");
   check(!valid(seven,actual),"missing requested fields detected");
   check(!valid(" , ",Collections.emptySet()),"empty override invalid");
  }finally {
   if(old==null)System.clearProperty("posassist.logDir");else System.setProperty("posassist.logDir",old);
   Files.deleteIfExists(root.resolve("config/posassist.properties"));Files.deleteIfExists(root.resolve("config"));Files.deleteIfExists(root);
  }
  System.out.println("SelfTestConfigTest: "+checks+" checks passed");
 }
}
