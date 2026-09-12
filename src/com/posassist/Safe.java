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
            Method method = method(target.getClass(), methodName, signature);
            if (method == null) {
                PosLog.warn("找不到方法: " + target.getClass().getName() + "." + methodName);
                return null;
            }
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable t) {
            PosLog.warn("呼叫失敗: " + target.getClass().getName() + "." + methodName, t);
            return null;
        }
    }

    /**
     * 沿著繼承鏈找方法，找不到才退回 getMethod。
     *
     * 不能只用 getMethod：EPB 框架有些我們需要的方法是 package-private
     * （ApplicationPool.getCreatorApplication、Block.getEffectiveTemplateClass），
     * getMethod 只看得到 public 的。跟 staticCall 那邊同一個理由、同一個做法。
     */
    private static Method method(Class<?> type, String methodName, Class<?>[] signature) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(methodName, signature);
            } catch (NoSuchMethodException notHere) {
                // 這層沒有就往上找
            }
        }
        try {
            return type.getMethod(methodName, signature);   // 介面上的 default 方法
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    /** 建構 EPB 的物件（例如 ApplicationHome）。失敗回 null。 */
    public static Object construct(String className, Class<?>[] signature, Object[] args) {
        Class<?> type = type(className);
        if (type == null) {
            return null;
        }
        try {
            java.lang.reflect.Constructor<?> constructor =
                type.getDeclaredConstructor(signature);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (Throwable t) {
            PosLog.warn("建構失敗: " + className, t);
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

    /**
     * 讀 private 欄位（沿繼承鏈找）。
     *
     * 跟上面的 field() 分開：那支用 getField，只看得到 public 欄位，POSN 的
     * Swing 元件剛好都是 public。但診斷時要讀的 EPB 內部狀態（例如
     * MainView.fullScreen）是 private 的，只能走 getDeclaredField。
     *
     * 只用於診斷 log —— 讀不到回 null，呼叫端要能接受少一項紀錄。
     */
    public static Object declaredField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        for (Class<?> current = target.getClass(); current != null;
             current = current.getSuperclass()) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException notHere) {
                // 這層沒有就往上找
            } catch (Throwable t) {
                PosLog.warn("讀私有欄位失敗: " + current.getName() + "." + fieldName);
                return null;
            }
        }
        return null;
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
