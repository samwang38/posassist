package com.posassist;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * org.json 的薄反射包裝。
 *
 * EPB 的 lib/org.json.jar 已內建 JSONObject / JSONArray，用反射取用可以維持外掛
 * 「零編譯期依賴」的原則，也不必自己寫 JSON parser。類別不在就整包回 null，
 * 呼叫端據此把預約功能關掉。
 */
public final class Json {

    private static final String OBJECT = "org.json.JSONObject";
    private static final String ARRAY = "org.json.JSONArray";

    private static boolean resolved;
    private static Constructor<?> parseObject;
    private static Method objGetObject;
    private static Method objGetArray;
    private static Method objHas;
    private static Method objOptString;
    private static Method objOptInt;
    private static Method arrLength;
    private static Method arrGetObject;

    private Json() {
    }

    /** org.json 在不在。不在的話預約功能整個不啟用。 */
    public static synchronized boolean available() {
        resolve();
        return parseObject != null;
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> object = Class.forName(OBJECT);
            Class<?> array = Class.forName(ARRAY);
            parseObject = object.getConstructor(String.class);
            objGetObject = object.getMethod("getJSONObject", String.class);
            objGetArray = object.getMethod("getJSONArray", String.class);
            objHas = object.getMethod("has", String.class);
            objOptString = object.getMethod("optString", String.class);
            objOptInt = object.getMethod("optInt", String.class, int.class);
            arrLength = array.getMethod("length");
            arrGetObject = array.getMethod("getJSONObject", int.class);
        } catch (Throwable t) {
            PosLog.warn("找不到 org.json，預約功能不啟用");
            parseObject = null;
        }
    }

    /** 解析一整包 JSON 物件。失敗回 null —— 不把原文放進 log，避免夾帶敏感內容。 */
    public static Object parse(String text) {
        resolve();
        if (parseObject == null || text == null) {
            return null;
        }
        try {
            return parseObject.newInstance(text);
        } catch (Throwable t) {
            PosLog.warn("JSON 解析失敗");
            return null;
        }
    }

    public static Object obj(Object node, String key) {
        if (node == null || !has(node, key)) {
            return null;
        }
        try {
            return objGetObject.invoke(node, key);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object arr(Object node, String key) {
        if (node == null || !has(node, key)) {
            return null;
        }
        try {
            return objGetArray.invoke(node, key);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean has(Object node, String key) {
        if (node == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(objHas.invoke(node, key));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取字串，取不到回空字串。 */
    public static String str(Object node, String key) {
        if (node == null) {
            return "";
        }
        try {
            Object value = objOptString.invoke(node, key);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static int num(Object node, String key, int fallback) {
        if (node == null) {
            return fallback;
        }
        try {
            Object value = objOptInt.invoke(node, key, Integer.valueOf(fallback));
            return value instanceof Integer ? ((Integer) value).intValue() : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static int size(Object array) {
        if (array == null) {
            return 0;
        }
        try {
            Object value = arrLength.invoke(array);
            return value instanceof Integer ? ((Integer) value).intValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static Object at(Object array, int index) {
        if (array == null) {
            return null;
        }
        try {
            return arrGetObject.invoke(array, Integer.valueOf(index));
        } catch (Throwable t) {
            return null;
        }
    }
}
