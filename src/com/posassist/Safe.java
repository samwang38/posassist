package com.posassist;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 對 EPB 內部類別的反射存取，全部包成「失敗就回 null」。
 *
 * 外掛刻意不在編譯期連結任何 EPB 類別：posassist.jar 用純 javac 就能建，
 * 而且 EPB 改版後就算某個欄位不見了，也只是該項不顯示，不會炸掉結帳畫面。
 */
public final class Safe {

    private Safe() {
    }

    public static Class<?> type(String className) {
        try {
            return Class.forName(className);
        } catch (Throwable t) {
            PosLog.warn("找不到類別: " + className);
            return null;
        }
    }

    public static Object staticCall(String className, String methodName,
                                    Class<?>[] signature, Object[] args) {
        Class<?> type = type(className);
        if (type == null) {
            return null;
        }
        try {
            Method method;
            try {
                method = type.getDeclaredMethod(methodName, signature);
            } catch (NoSuchMethodException notDeclared) {
                method = type.getMethod(methodName, signature);
            }
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (Throwable t) {
            PosLog.warn("靜態呼叫失敗: " + className + "." + methodName, t);
            return null;
        }
    }

    public static Object call(Object target, String methodName,
                              Class<?>[] signature, Object[] args) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName, signature);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable t) {
            PosLog.warn("呼叫失敗: " + target.getClass().getName() + "." + methodName, t);
            return null;
        }
    }

    public static Object call(Object target, String methodName) {
        return call(target, methodName, new Class<?>[0], new Object[0]);
    }

    /** 讀 public 欄位。POSN 的 Swing 元件都是 public 欄位。 */
    public static Object field(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getField(fieldName).get(target);
        } catch (Throwable t) {
            PosLog.warn("讀欄位失敗: " + target.getClass().getName() + "." + fieldName);
            return null;
        }
    }

    /** 對任何有 getText() 的元件取字串，取不到回空字串。 */
    public static String text(Object component) {
        Object value = call(component, "getText");
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 動態實作 EPB 的介面，免去編譯期依賴。 */
    public static Object proxy(String interfaceName, InvocationHandler handler) {
        Class<?> type = type(interfaceName);
        if (type == null) {
            return null;
        }
        try {
            return Proxy.newProxyInstance(
                type.getClassLoader() != null
                    ? type.getClassLoader()
                    : Safe.class.getClassLoader(),
                new Class<?>[] { type },
                handler);
        } catch (Throwable t) {
            PosLog.warn("建立 proxy 失敗: " + interfaceName, t);
            return null;
        }
    }

    /** 任何跟 EPB 互動的動作都包這層，確保例外不會冒進 EPB 的 EDT。 */
    public static void guard(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            PosLog.warn("已攔截例外: " + what, t);
        }
    }
}
