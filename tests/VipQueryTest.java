package com.posassist;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
/**
 * VipQuery.rows() 的組列邏輯。用 Proxy 假造 JDBC，不連任何資料庫 ——
 * 它取代的是 EPB 的 DatabaseUtility.getResult，回傳型別與截斷行為必須一致。
 */
public final class VipQueryTest {
 static int checks;
 static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 static List<Object> bound=new ArrayList<Object>();
 static int maxRowsSeen=-99;

 /** 回傳固定資料的假 ResultSet/Statement/Connection。null 欄位原樣保留。 */
 static Connection fake(final Object[][] data,final int columns){
  bound.clear();maxRowsSeen=-99;
  final int[] cursor={0};
  final InvocationHandler meta=new InvocationHandler(){
   public Object invoke(Object p,Method m,Object[] a){
    if("getColumnCount".equals(m.getName()))return Integer.valueOf(columns);
    return def(m);
   }};
  final InvocationHandler rs=new InvocationHandler(){
   public Object invoke(Object p,Method m,Object[] a){
    String name=m.getName();
    if("next".equals(name))return Boolean.valueOf(cursor[0]++<data.length);
    if("getMetaData".equals(name))return Proxy.newProxyInstance(
      VipQueryTest.class.getClassLoader(),new Class<?>[]{ResultSetMetaData.class},meta);
    if("getObject".equals(name))return data[cursor[0]-1][((Integer)a[0]).intValue()-1];
    return def(m);
   }};
  final InvocationHandler statement=new InvocationHandler(){
   public Object invoke(Object p,Method m,Object[] a){
    String name=m.getName();
    if("setMaxRows".equals(name)){maxRowsSeen=((Integer)a[0]).intValue();return null;}
    if("setObject".equals(name)){bound.add(a[1]);return null;}
    if("executeQuery".equals(name))return Proxy.newProxyInstance(
      VipQueryTest.class.getClassLoader(),new Class<?>[]{ResultSet.class},rs);
    return def(m);
   }};
  return (Connection)Proxy.newProxyInstance(VipQueryTest.class.getClassLoader(),
    new Class<?>[]{Connection.class},new InvocationHandler(){
   public Object invoke(Object p,Method m,Object[] a){
    if("prepareStatement".equals(m.getName()))return Proxy.newProxyInstance(
      VipQueryTest.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},statement);
    return def(m);
   }});
 }
 static Object def(Method m){
  Class<?> t=m.getReturnType();
  if(!t.isPrimitive())return null;
  if(t==boolean.class)return Boolean.FALSE;
  if(t==int.class)return Integer.valueOf(0);
  if(t==void.class)return null;
  return Integer.valueOf(0);
 }

 public static void main(String[] args)throws Exception {
  Object[][] three={{"A001","王小明",null},{"A002","李小華","a@b.c"},{"A003",null,"d@e.f"}};

  List<Vector> all=VipQuery.rows(fake(three,3),"SELECT 1",null,-1);
  check(all.size()==3,"unlimited returns every row");
  check(all.get(0).size()==3,"row width follows column count");
  check("A001".equals(all.get(0).get(0)),"first cell mapped");
  check(all.get(0).get(2)==null,"null cell kept as null, not \"\"");
  check(all.get(2).get(1)==null,"null in the middle column kept");
  check(maxRowsSeen==-99,"unlimited does not call setMaxRows");

  List<Vector> capped=VipQuery.rows(fake(three,3),"SELECT 1",null,2);
  check(capped.size()==2,"maxRows truncates even when driver ignores setMaxRows");
  check(maxRowsSeen==2,"maxRows passed to the driver as a hint too");

  List<Vector> empty=VipQuery.rows(fake(new Object[0][],3),"SELECT 1",null,10);
  check(empty.isEmpty(),"no rows is an empty list, never null");

  VipQuery.rows(fake(three,3),"SELECT 1",
    Arrays.asList((Object)"01",(Object)"A001",(Object)null),-1);
  check(bound.size()==3,"every parameter bound once");
  check("01".equals(bound.get(0))&&"A001".equals(bound.get(1)),"parameters bound in order");
  check(bound.get(2)==null,"null parameter bound as null");

  // 欄位數比實際資料少：只取前 n 欄，不能越界
  List<Vector> narrow=VipQuery.rows(fake(three,2),"SELECT 1",null,-1);
  check(narrow.get(0).size()==2,"column count wins over data width");

  System.out.println("VipQueryTest: "+checks+" checks passed");
 }
}
