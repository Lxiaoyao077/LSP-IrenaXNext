package io.github.libxposed.api;

import android.app.AppComponentFactory;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.List;

import io.github.libxposed.api.annotations.XposedApiMin;

/**
 * Interface for module initialization.
 *
 * <p>Superset of the API 100, API 101 and API 102 lifecycle surfaces. Modules compiled against
 * any version override the callbacks they know; the framework invokes every callback and modules
 * that do not override a given callback simply receive the default.</p>
 */
@SuppressWarnings("unused")
public interface XposedModuleInterface {

    /**
     * Wraps information about the process in which the module is loaded.
     * This information only indicates the state at the time of loading and will not be updated.
     */
    interface ModuleLoadedParam {
        /**
         * Returns whether the current process is system server.
         */
        boolean isSystemServer();

        /**
         * Gets the process name.
         */
        @NonNull
        String getProcessName();
    }

    /**
     * Wraps information about the package being loaded.
     * <p>
     * Note that API 100 exposed {@link #getClassLoader()} directly on this interface; API 101 moved
     * it to {@link PackageReadyParam}. The superset keeps both accessors so that modules of either
     * generation can read the classloader they expect.
     * </p>
     */
    interface PackageLoadedParam {
        /**
         * Gets the package name of the current package.
         */
        @NonNull
        String getPackageName();

        /**
         * Gets the {@link ApplicationInfo} of the current package.
         */
        @NonNull
        ApplicationInfo getApplicationInfo();

        /**
         * Returns whether this is the first and main package loaded in the process.
         */
        boolean isFirstPackage();

        /**
         * Gets the default classloader of the current package. This is the classloader that loads
         * the package's code, resources and custom {@link AppComponentFactory}.
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        @NonNull
        ClassLoader getDefaultClassLoader();

        /**
         * Gets the classloader of the package being loaded (API 100).
         */
        @NonNull
        ClassLoader getClassLoader();
    }

    /**
     * Wraps information about the package whose classloader is ready (API 101).
     */
    interface PackageReadyParam extends PackageLoadedParam {
        /**
         * Gets the {@link AppComponentFactory} of the current package.
         */
        @RequiresApi(Build.VERSION_CODES.P)
        @NonNull
        AppComponentFactory getAppComponentFactory();
    }

    /**
     * Wraps information about system server (API 100).
     */
    interface SystemServerLoadedParam {
        /**
         * Gets the class loader of system server.
         */
        @NonNull
        ClassLoader getClassLoader();
    }

    /**
     * Wraps information about system server (API 101).
     */
    interface SystemServerStartingParam {
        /**
         * Gets the class loader of system server.
         */
        @NonNull
        ClassLoader getClassLoader();
    }

    /**
     * Wraps information about the hot reloading event (API 102).
     */
    @XposedApiMin(102)
    interface HotReloadingParam {
        /**
         * Gets the data passed from the module app when triggering hot reload through the service.
         * This can be {@code null} if the app passes {@code null} or the hot reload is triggered by
         * app updating. The bundle should contain only values that can be unmarshalled without the
         * module's class loader, such as primitive values, strings, arrays and framework
         * {@link Bundle} instances.
         */
        @Nullable
        Bundle getExtras();

        /**
         * Sets the data to be passed to the new code after hot reloading. This can be retrieved in
         * {@link #onHotReloaded(HotReloadedParam)}. The saved state must not contain objects created
         * under the old module classloader, because retaining them in the new generation can keep
         * the old generation strongly reachable after hot reload. Use classloader-neutral values to
         * transfer state.
         *
         * @param outState The data to be passed to the new code after hot reloading
         * @throws IllegalArgumentException if {@code outState} contains an object detected as being
         *                                  created under the old module classloader
         */
        void setSavedInstanceState(@Nullable Object outState);
    }

    /**
     * Wraps information about the hot reloaded event (API 102).
     */
    @XposedApiMin(102)
    interface HotReloadedParam extends ModuleLoadedParam {
        /**
         * Gets the data passed from the module app when triggering hot reload. This can be
         * {@code null} if the app passes {@code null} or the hot reload is triggered by app
         * updating.
         */
        @Nullable
        Bundle getExtras();

        /**
         * Gets the data set in {@link HotReloadingParam#setSavedInstanceState(Object)}.
         */
        @Nullable
        Object getSavedInstanceState();

        /**
         * Gets a list of hook handles created by the previous generation of this module. The new
         * code can choose to remove or atomically replace these hooks with new ones through
         * {@link XposedInterface.HookHandle#replaceHook(XposedInterface.Hooker)}.
         */
        @NonNull
        List<XposedInterface.HookHandle> getOldHookHandles();
    }

    /**
     * Gets notified when the module is loaded into the target process (API 101).<br/>
     * This callback is guaranteed to be called exactly once for a process.
     */
    default void onModuleLoaded(@NonNull ModuleLoadedParam param) {
    }

    /**
     * Gets notified when a {@link android.R.attr#hasCode} package is loaded into the process.
     * This is the time when the default classloader is ready but before the instantiation of
     * {@link AppComponentFactory}.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    default void onPackageLoaded(@NonNull PackageLoadedParam param) {
    }

    /**
     * Gets notified when {@link AppComponentFactory} has instantiated the classloader
     * and is ready to create {@link android.app.Application} (API 101).
     */
    default void onPackageReady(@NonNull PackageReadyParam param) {
    }

    /**
     * Gets notified when the system server is loaded (API 100).
     */
    default void onSystemServerLoaded(@NonNull SystemServerLoadedParam param) {
    }

    /**
     * Gets notified when system server is ready to start critical services (API 101).
     */
    default void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
    }

    /**
     * Gets notified when the module is about to be reloaded (API 102). This callback is called when
     * hot reloading is triggered through the service, or by app updating if {@code autoHotReload} is
     * set to true in {@code module.prop}.
     *
     * <p>This callback runs in <b>old</b> code.</p>
     * <p>Returning {@code true} declares that the old generation is ready to be retired. Before
     * returning {@code true}, modules must stop all module-owned Java and native threads,
     * unregister native hooks and external callbacks, release JNI global references to
     * module-classloader objects, and clear references to module objects stored by system or app
     * classes.</p>
     * <p>Returning {@code false} rejects the hot reload request.</p>
     *
     * @param param Information about the hot reloading event
     * @return {@code true} to allow hot reloading to proceed, {@code false} to cancel hot reloading
     */
    @XposedApiMin(102)
    default boolean onHotReloading(@NonNull HotReloadingParam param) {
        return false;
    }

    /**
     * Gets notified when the module has been reloaded (API 102).
     *
     * <p>This callback runs in <b>new</b> code.</p>
     * <p>Package lifecycle callbacks are not automatically replayed after hot reload. Override this
     * method to atomically replace old hooks through
     * {@link XposedInterface.HookHandle#replaceHook(XposedInterface.Hooker)}, remove hooks that
     * should not survive, or perform reload-specific initialization. The default implementation
     * only unhooks all old hooks.</p>
     *
     * @param param Information about the hot reloaded event
     */
    @XposedApiMin(102)
    default void onHotReloaded(@NonNull HotReloadedParam param) {
        param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);
    }
}