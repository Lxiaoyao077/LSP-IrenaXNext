package org.lsposed.lspd.nativebridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import dalvik.annotation.optimization.FastNative;

public class HookBridge {

    /**
     * apiMode for {@link #hookMethod} / {@link #unhookMethod}:
     * {@link #API_MODE_LEGACY} = classic Xposed API (de.robv.android.xposed) callbacks,
     * {@link #API_MODE_100} = libxposed API 100 before/after hooker callbacks,
     * {@link #API_MODE_101} = libxposed API 101 intercept hookers.
     */
    public static final int API_MODE_LEGACY = 0;
    public static final int API_MODE_100 = 1;
    public static final int API_MODE_101 = 2;

    public static native boolean hookMethod(int apiMode, Executable hookMethod, Class<?> hooker, int priority, Object callback);

    public static native boolean unhookMethod(int apiMode, Executable hookMethod, Object callback);

    /**
     * Swaps {@code oldCallback} for {@code newCallback} on the hook already registered for
     * {@code hookMethod}, under the same lock {@link #callbackSnapshot} takes, so no snapshot can
     * observe both or neither.
     *
     * <p>A snapshot taken before this returns keeps running {@code oldCallback}: it copied the
     * reference into an array of its own. That is what makes a replacement invisible to a call
     * already in flight, which is what {@code HookHandle#replaceHook} promises.</p>
     *
     * <p>Returns false when {@code oldCallback} is no longer registered, which is the caller's cue
     * that the handle it holds has already been replaced or unhooked.</p>
     *
     * @param newPriority the priority the replacement must occupy, which keeps the hook where it
     *                    was in the chain instead of moving it behind its peers
     */
    public static native boolean replaceCallback(int apiMode, Executable hookMethod, Object oldCallback, Object newCallback, int newPriority);

    /**
     * The class name prefixes of the legacy {@code de.robv.android.xposed} API as this process will
     * actually be asked for them, which is not the same as what they are called in source: dex
     * obfuscation rewrites them, in the framework and in every module, to a different random string
     * on every boot.
     *
     * <p>A module targeting API 102 is not allowed to reach any of them, and the module class loader
     * is the only place that can be enforced.</p>
     */
    public static native String[] legacyApiPrefixes();

    public static native boolean deoptimizeMethod(Executable method);

    public static native <T> T allocateObject(Class<T> clazz) throws InstantiationException;

    public static native <T> T allocateSpecialReceiver(Constructor<?> constructor, Class<T> clazz) throws InstantiationException;

    public static native Method findClassInitializer(Class<?> clazz);

    public static native Object invokeOriginalMethod(Executable method, Object thisObject, Object[] args, boolean isConstructor) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException;

    public static Object invokeOriginalMethod(Executable method, Object thisObject, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        return invokeOriginalMethod(method, thisObject, args,
                method instanceof Constructor && !Modifier.isStatic(method.getModifiers()));
    }

    public static native <T> Object invokeSpecialMethod(Executable method, Class<T> clazz, Object thisObject, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, InstantiationException;

    public static Object invokeSpecialMethod(Executable method, Object thisObject, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, InstantiationException {
        return invokeSpecialMethod(method, null, thisObject, args);
    }

    @FastNative
    public static native boolean instanceOf(Object obj, Class<?> clazz);

    @FastNative
    public static native boolean setTrusted(Object cookie);

    @FastNative
    public static native int gettid();

    /**
     * Snapshot of all registered callbacks for a hooked method.
     * Index 0: libxposed API 100 hooker callbacks, index 1: classic Xposed callbacks,
     * index 2: libxposed API 101 hookers.
     */
    public static native Object[][] callbackSnapshot(Class<?> hooker_callback, Executable method);

    /**
     * Snapshot of the libxposed API 101 hookers registered for a method whose priority is
     * not greater than {@code maxPriority}, in descending priority order.
     */
    public static native Object[] callbackSnapshot101(Executable method, int maxPriority);
}