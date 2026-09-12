package com.posassist;
import java.util.*;
/** Uses actual EPB CriteriaItem in an isolated JVM, with no login or database call. */
public final class NativeCriteriaLiveCheck {
 public static void main(String[] args)throws Exception {
  Class<?> type=Class.forName("com.epb.framework.CriteriaItem");
  Object single=type.getConstructor(String.class,Class.class).newInstance("stkId",String.class);
  type.getMethod("setKeyWord",String.class).invoke(single,type.getField("KW_EQUALS_TO").get(null));
  type.getMethod("setValue",Object.class).invoke(single,"07312334");
  if(!NativeInventorySelection.read(new HashSet<Object>(Arrays.asList(single))).equals(Arrays.asList("07312334")))throw new AssertionError("native equals format");
  Object multi=type.getConstructor(String.class,Class.class).newInstance("stkId",String.class);
  type.getMethod("setKeyWord",String.class).invoke(multi,type.getField("KW_IN").get(null));
  type.getMethod("addValues",List.class).invoke(multi,Arrays.asList("09600040","50300018"));
  if(!NativeInventorySelection.read(new HashSet<Object>(Arrays.asList(multi))).equals(Arrays.asList("09600040","50300018")))throw new AssertionError("native IN format");
  System.out.println("Actual EPB CriteriaItem: equals and IN passed; no native UI/login exercised");
 }
}
