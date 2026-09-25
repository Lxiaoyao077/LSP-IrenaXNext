package org.lsposed.lspd.service;

/**
 * The reverse half of the daemon &lt;-&gt; injected process channel.
 *
 * <p>{@link ILSPApplicationService} and {@link ILSPInjectedModuleService} are both implemented by
 * the daemon and called by the injected process. API 102 needs the opposite direction: the daemon
 * has to ask a target process which module generation it is running, and to make it reload. A
 * process registers one of these through
 * {@link ILSPApplicationService#registerProcessService(ILSPProcessService)} right after it obtained
 * its application service.
 * </p>
 */
interface ILSPProcessService {
    /** Bundle key: the raw status of a hot reload, one of the HOT_RELOAD_* codes below. */
    const String RESULT_STATUS = "status";
    /** Bundle key: the optional diagnostic message, absent when there is none. */
    const String RESULT_MESSAGE = "message";

    /**
     * Raw hot reload status codes.
     *
     * <p>These mirror what the API 102 {@code IXposedService} AIDL puts on the wire and have to keep
     * those exact values: the daemon forwards them to the module app as plain ints, and the module
     * app compares them against its own copy of that AIDL. Declaring them here is what keeps the
     * daemon and the process side from drifting apart.
     * </p>
     */
    const int HOT_RELOAD_SUCCEEDED = 0;
    const int HOT_RELOAD_FAILED = 1;
    const int HOT_RELOAD_UNSUPPORTED = 2;
    const int HOT_RELOAD_IN_PROGRESS = 3;
    const int HOT_RELOAD_PROCESS_DIED = 4;

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
     * @return a Bundle carrying RESULT_STATUS and, when there is one, RESULT_MESSAGE
     */
    Bundle hotReloadModule(String packageName, in Bundle extras);
}
