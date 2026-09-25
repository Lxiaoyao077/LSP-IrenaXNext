package org.lsposed.lspd.service;

import org.lsposed.lspd.models.HotReloadResult;

/**
 * The reverse half of the daemon &lt;-&gt; injected process channel.
 *
 * <p>{@link ILSPApplicationService} and {@link ILSPInjectedModuleService} are both implemented by
 * the daemon and called by the injected process. API 102 needs the opposite direction: the daemon
 * has to ask a target process about the module generation it is running, and to make it reload.
 * A process registers one of these through
 * {@link ILSPApplicationService#registerProcessService(ILSPProcessService)} right after it obtained
 * its application service.
 * </p>
 */
interface ILSPProcessService {
    /**
     * Gets the module APK this process loaded the module from, or {@code null} when the module is
     * not loaded in this process.
     *
     * <p>The framework uses this as the module generation identity: an updated module is always
     * installed under a new APK path, so a process whose loaded APK differs from the installed one
     * is running stale code and can be hot reloaded.
     * </p>
     */
    String getLoadedModuleApk(String packageName);

    /**
     * Gets the version code of the module code loaded in this process, or {@code -1} when the
     * module is not loaded in this process. This is a diagnostic value only.
     */
    long getLoadedModuleVersionCode(String packageName);

    /**
     * Hot reloads a module inside this process, replacing the module generation it is running.
     *
     * @param extras classloader-neutral data to hand to the old generation, as passed by the
     *               module app to {@code IXposedService#hotReloadModule}
     * @return the raw status and the optional diagnostic message
     */
    HotReloadResult hotReloadModule(String packageName, in Bundle extras);
}
