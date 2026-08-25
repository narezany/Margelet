package de.robv.android.xposed;

import org.telegram.margelet.hook.MargeletHook;

import java.lang.reflect.Member;

/**
 * XposedBridge Compatibility Layer for Margelet plugins.
 */
public class XposedBridge {

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        MargeletHook.hookMethod(hookMethod, callback);
        return new XC_MethodHook.Unhook(hookMethod, callback);
    }

    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
        MargeletHook.unhookMethod(hookMethod, callback);
    }
}
