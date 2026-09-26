package org.lsposed.lspd;

import rikka.parcelablelist.ParcelableListSlice;
import org.lsposed.lspd.models.UserInfo;
import org.lsposed.lspd.models.Application;


interface ILSPManagerService {
    const int DEX2OAT_OK = 0;
    const int DEX2OAT_CRASHED = 1;
    const int DEX2OAT_MOUNT_FAILED = 2;
    const int DEX2OAT_SELINUX_PERMISSIVE = 3;
    const int DEX2OAT_SEPOLICY_INCORRECT = 4;

    /** The Dobby engine. Available on every ABI. */
    const int INLINE_HOOK_BACKEND_DOBBY = 0;

    /** The ShadowHook engine. Built for arm64-v8a and armeabi-v7a only. */
    const int INLINE_HOOK_BACKEND_SHADOWHOOK = 1;

    String getApi() = 1;

    ParcelableListSlice<PackageInfo> getInstalledPackagesFromAllUsers(int flags, boolean filterNoProcess) = 2;

    List<Application> enabledModules() = 3;

    boolean enableModule(String packageName, int userId) = 4;

    boolean disableModule(String packageName, int userId) = 5;

    boolean setModuleScope(String packageName, in List<Application> scope) = 6;

    List<Application> getModuleScope(String packageName) = 7;

    boolean isVerboseLog() = 11;

    void setVerboseLog(boolean enabled) = 12;

    boolean isModulesLogEnabled() = 13;

    void setModulesLogEnabled(boolean enabled) = 14;

    ParcelFileDescriptor getVerboseLog() = 16;

    ParcelFileDescriptor getModulesLog() = 17;

    int getXposedVersionCode() = 18;

    String getXposedVersionName() = 19;

    int getXposedApiVersion() = 20;

    boolean clearLogs(boolean verbose) = 21;

    PackageInfo getPackageInfo(String packageName, int flags, int uid) = 22;

    void forceStopPackage(String packageName, int userId) = 23;

    void reboot() = 24;

    boolean uninstallPackage(String packageName, int userId) = 25;

    boolean isSepolicyLoaded() = 26;

    List<UserInfo> getUsers() = 27;

    int installExistingPackageAsUser(String packageName, int userId) = 28;

    boolean systemServerRequested() = 29;

    int startActivityAsUserWithFeature(in Intent intent,  int userId) = 30;

    ParcelableListSlice<ResolveInfo> queryIntentActivitiesAsUser(in Intent intent, int flags, int userId) = 31;

    boolean dex2oatFlagsLoaded() = 32;

    void setHiddenIcon(boolean hide) = 33;

    void getLogs(in ParcelFileDescriptor zipFd) = 34;

    void restartFor(in Intent intent) = 35;

    oneway void flashZip(String zipPath, in ParcelFileDescriptor outputStream) = 39;

    boolean performDexOptMode(String packageName) = 40;

    List<String> getDenyListPackages() = 41;

    boolean getDexObfuscate() = 42;

    void setDexObfuscate(boolean enable) = 43;

    int getDex2OatWrapperCompatibility() = 44;

    void clearApplicationProfileData(in String packageName) = 45;

    boolean enableStatusNotification() = 47;

    void setEnableStatusNotification(boolean enable) = 48;

    boolean getAutoInclude(String packageName) = 49;

    boolean setAutoInclude(String packageName, boolean enable) = 50;
    
    boolean isModulePrefsExist(String packageName, int userId) = 53;

    boolean deleteModulePrefs(String packageName, int userId) = 54;

    void removeBlockedScopeRequest(String packageName, int userId) = 55;

    int getInlineHookBackend() = 56;

    /**
     * Processes already running keep the engine they started with.
     */
    void setInlineHookBackend(int backend) = 57;

    // ---- reached by the ported manager, which asks for what its own daemon offers ----
    // The VectorXposed-it UI is written against a richer daemon interface, and these are the calls
    // it makes that irena had no equivalent of. Additive: the transaction numbers are new and the
    // existing surface is untouched.
    const int ALL_USERS = -1;

    const int ROOT_UNKNOWN = 0;
    const int ROOT_NONE = 1;
    const int ROOT_MULTIPLE = 2;
    const int ROOT_MAGISK = 3;
    const int ROOT_KERNELSU = 4;
    const int ROOT_APATCH = 5;

    /** Restarts zygote, and with it every process the framework is loaded into. */
    void softReboot() = 58;

    /** One of ROOT_*, by whichever of the well-known module roots this device has. */
    int getRootImplementation() = 59;

    /** The flashed manager APK, or null when it is absent or fails its own signature check. */
    ParcelFileDescriptor getManagerApk() = 60;

    /** The rotated log files the daemon still holds, oldest first. */
    List<String> getLogParts(boolean verbose) = 61;

    /** One of those by name, or null when it is gone or the name does not name one of them. */
    ParcelFileDescriptor getLogPart(boolean verbose, String name) = 62;
}
