package org.telegram.margelet.hook;

import org.telegram.margelet.MargeletPluginHost;
import org.telegram.messenger.FileLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Архитектура динамических хуков Margelet.
 * Позволяет плагинам перехватывать и модифицировать любые методы Telegram на лету.
 */
public class MargeletHook {

    private static final Map<Member, CopyOnWriteArrayList<IHookCallback>> hookedMethods = new ConcurrentHashMap<>();
    private static final Map<String, List<Unhook>> pluginUnhooks = new ConcurrentHashMap<>();

    public interface Unhook {
        void unhook();
    }

    /**
     * Зарегистрировать хук на метод.
     */
    public static Unhook hookMethod(Member method, IHookCallback callback) {
        return hookMethod(null, method, callback);
    }

    public static synchronized Unhook hookMethod(String pluginId, Member method, IHookCallback callback) {
        if (method == null || callback == null) {
            return () -> {};
        }

        CopyOnWriteArrayList<IHookCallback> callbacks = hookedMethods.get(method);
        if (callbacks == null) {
            callbacks = new CopyOnWriteArrayList<>();
            hookedMethods.put(method, callbacks);
        }

        callbacks.add(callback);

        Unhook unhook = () -> {
            CopyOnWriteArrayList<IHookCallback> list = hookedMethods.get(method);
            if (list != null) {
                list.remove(callback);
                if (list.isEmpty()) {
                    hookedMethods.remove(method);
                }
            }
        };

        if (pluginId != null) {
            List<Unhook> unhooks = pluginUnhooks.computeIfAbsent(pluginId, k -> new ArrayList<>());
            synchronized (unhooks) {
                unhooks.add(unhook);
            }
        }

        return unhook;
    }

    /**
     * Снять все хуки конкретного плагина.
     */
    public static void unhookAll(String pluginId) {
        if (pluginId == null) return;
        List<Unhook> unhooks = pluginUnhooks.remove(pluginId);
        if (unhooks != null) {
            synchronized (unhooks) {
                for (Unhook u : unhooks) {
                    try {
                        u.unhook();
                    } catch (Throwable ignored) {
                    }
                }
                unhooks.clear();
            }
        }
    }

    /**
     * Снять один хук. Возвращает true, если колбэк действительно стоял.
     */
    public static boolean unhookMethod(Member method, IHookCallback callback) {
        if (method == null || callback == null) return false;
        CopyOnWriteArrayList<IHookCallback> callbacks = hookedMethods.get(method);
        if (callbacks == null) return false;
        final boolean removed = callbacks.remove(callback);
        if (callbacks.isEmpty()) {
            hookedMethods.remove(method);
        }
        return removed;
    }

    private static int priorityOf(IHookCallback callback) {
        return callback instanceof HookCallback ? ((HookCallback) callback).priority : 50;
    }

    /**
     * Колбэки в порядке приоритета: выше — раньше. Без этого поле priority
     * было бы записью, на которую никто не смотрит.
     */
    private static List<IHookCallback> ordered(CopyOnWriteArrayList<IHookCallback> callbacks) {
        final ArrayList<IHookCallback> copy = new ArrayList<>(callbacks);
        Collections.sort(copy, (a, b) -> priorityOf(b) - priorityOf(a));
        return copy;
    }

    public static boolean hasHooks(Member method) {
        CopyOnWriteArrayList<IHookCallback> callbacks = hookedMethods.get(method);
        return callbacks != null && !callbacks.isEmpty();
    }

    /**
     * Точка входа вызова хуков перед оригинальным методом.
     */
    public static MethodHookParam callBefore(Member method, Object thisObject, Object[] args) {
        CopyOnWriteArrayList<IHookCallback> registered = hookedMethods.get(method);
        if (registered == null || registered.isEmpty()) {
            return null;
        }

        MethodHookParam param = new MethodHookParam();
        param.method = method;
        param.thisObject = thisObject;
        param.args = args != null ? args : new Object[0];

        for (IHookCallback callback : ordered(registered)) {
            try {
                callback.beforeHookedMethod(param);
            } catch (Throwable t) {
                FileLog.e(t);
                MargeletPluginHost.log("MargeletHook", "Error in beforeHookedMethod: " + t, true);
            }
            if (param.hasResult()) {
                break;
            }
        }

        return param;
    }

    /**
     * Точка входа вызова хуков после оригинального метода.
     */
    public static void callAfter(MethodHookParam param, Object result, Throwable throwable) {
        if (param == null) return;
        if (!param.hasResult()) {
            if (throwable != null) {
                param.setThrowable(throwable);
            } else {
                param.setResult(result);
            }
        }

        CopyOnWriteArrayList<IHookCallback> registered = hookedMethods.get(param.method);
        if (registered == null || registered.isEmpty()) {
            return;
        }

        for (IHookCallback callback : ordered(registered)) {
            try {
                callback.afterHookedMethod(param);
            } catch (Throwable t) {
                FileLog.e(t);
                MargeletPluginHost.log("MargeletHook", "Error in afterHookedMethod: " + t, true);
            }
        }
    }

    // --- Утилиты рефлексии (Reflection Helpers) ---

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader != null ? classLoader : MargeletHook.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    public static Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        if (clazz == null || methodName == null) return null;
        try {
            if (parameterTypes != null && parameterTypes.length > 0) {
                Method m = clazz.getDeclaredMethod(methodName, parameterTypes);
                m.setAccessible(true);
                return m;
            }
        } catch (Throwable ignored) {
        }
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        if (obj == null || fieldName == null) return null;
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        if (obj == null || fieldName == null) return;
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable ignored) {
        }
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        if (obj == null || methodName == null) return null;
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] != null ? args[i].getClass() : Object.class;
            }
            Method m = findMethod(obj.getClass(), methodName, types);
            if (m != null) {
                return m.invoke(obj, args);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
