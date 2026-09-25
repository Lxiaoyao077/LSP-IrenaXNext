/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.lspd.service;

import static org.lsposed.lspd.service.PackageService.PER_USER_RANGE;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;

import org.lsposed.daemon.BuildConfig;
import org.lsposed.lspd.models.Module;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.service.IXposedScopeCallback;
import io.github.libxposed.service.IXposedService;

public class LSPModuleService extends IXposedService.Stub {

    // Highest libxposed API version implemented by this framework. Modules declaring a
    // higher minApiVersion are rejected at load time (see ConfigFileManager#loadModule).
    static final int XPOSED_API_VERSION = XposedInterface.LIB_API;

    // Lowest libxposed API version this framework still loads. A module built against an older API
    // than this one is refused rather than loaded: 101 replaced the callback engine with the
    // interceptor chain and moved the classloader to the module entry, so the framework serves 101
    // onwards.
    static final int MIN_SUPPORTED_API_VERSION = XposedInterface.API_101;

    private final static String TAG = "LSPosedModuleService";

    // API 102: running targets and hot reload.
    //
    // Same story as the 101 wire below: the IXposedService AIDL this tree pins predates API 102, so
    // these calls are not on the generated stub and arrive as raw transactions. The API 102 AIDL
    // declares
    //   getRunningTargets()                               = 13 -> wire 14
    //   hotReloadModule(long, Bundle, IHotReloadCallback) = 14 -> wire 15
    // which sits above everything the API 100 AIDL used (it stopped at 13), so nothing is shadowed
    // and the module app's generated stub, built from the 102 AIDL, sends exactly these codes.
    private static final int TRANSACTION_GET_RUNNING_TARGETS = 14;
    private static final int TRANSACTION_HOT_RELOAD_MODULE = 15;

    // Raw HOT_RELOAD_* status codes. They travel on to the module app as plain ints, so they have to
    // match its copy of the API 102 IXposedService AIDL exactly; they are spelled out in our own
    // AIDL so the daemon and the process side cannot drift apart.
    static final int HOT_RELOAD_SUCCEEDED = ILSPProcessService.HOT_RELOAD_SUCCEEDED;
    static final int HOT_RELOAD_FAILED = ILSPProcessService.HOT_RELOAD_FAILED;
    static final int HOT_RELOAD_UNSUPPORTED = ILSPProcessService.HOT_RELOAD_UNSUPPORTED;
    static final int HOT_RELOAD_IN_PROGRESS = ILSPProcessService.HOT_RELOAD_IN_PROGRESS;
    static final int HOT_RELOAD_PROCESS_DIED = ILSPProcessService.HOT_RELOAD_PROCESS_DIED;

    private static final String HOT_RELOAD_CALLBACK_DESCRIPTOR = "io.github.libxposed.service.IHotReloadCallback";
    // oneway void onHotReloadResult(int status, String message) = 1
    private static final int TRANSACTION_ON_HOT_RELOAD_RESULT = IBinder.FIRST_CALL_TRANSACTION + 1;

    private static final ExecutorService hotReloadExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "module-hot-reload"));

    /** One process this module is loaded into, as the daemon sees it. */
    private static final class TargetKey {
        final int uid;
        final int pid;
        final String processName;

        TargetKey(int uid, int pid, String processName) {
            this.uid = uid;
            this.pid = pid;
            this.processName = processName;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof TargetKey)) return false;
            var key = (TargetKey) o;
            return uid == key.uid && pid == key.pid && Objects.equals(processName, key.processName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uid, pid, processName);
        }
    }

    // Target ids are handed out here rather than derived from the pid: a module must not be able to
    // fabricate an id for a process it was never told about, and a pid reused by a later process
    // must not inherit the old target. All three maps are guarded by targetLock.
    private final Map<Long, TargetKey> targetsById = new HashMap<>();
    private final Map<TargetKey, Long> targetIdsByKey = new HashMap<>();
    private final Set<Long> reloadingTargets = new HashSet<>();
    private final Set<Long> failedTargets = new HashSet<>();
    private long nextTargetId = 0;
    private final Object targetLock = new Object();

    private final static Set<Integer> uidSet = ConcurrentHashMap.newKeySet();
    private final static Set<ModuleBinderKey> sentBinderSet = ConcurrentHashMap.newKeySet();
    private final static Set<ModuleBinderKey> sendingBinderSet = ConcurrentHashMap.newKeySet();
    private final static Map<Module, LSPModuleService> serviceMap = Collections.synchronizedMap(new WeakHashMap<>());
    private final static ExecutorService binderExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "module-binder-delivery"));

    public final static String FILES_DIR = "files";

    private final @NonNull
    Module loadedModule;

    static void uidClear() {
        uidSet.clear();
        sentBinderSet.clear();
        sendingBinderSet.clear();
    }

    static void uidStarts(int uid) {
        if (uidSet.add(uid)) {
            sendBinderForUid(uid);
        }
    }

    static void uidGone(int uid) {
        uidSet.remove(uid);
        sentBinderSet.removeIf(k -> k.uid == uid);
        sendingBinderSet.removeIf(k -> k.uid == uid);
    }

    static void sendBindersForRunningModules() {
        for (int uid : uidSet) {
            sendBinderForUid(uid);
        }
    }

    static void sendBinderForRunningModule(String packageName) {
        for (int uid : uidSet) {
            var module = ConfigManager.getInstance().getModule(uid);
            if (module != null && Objects.equals(module.packageName, packageName)) {
                sendBinderForModule(module, uid);
            }
        }
    }

    private static void sendBinderForUid(int uid) {
        var module = ConfigManager.getInstance().getModule(uid);
        if (module != null) {
            sendBinderForModule(module, uid);
        }
    }

    private static void sendBinderForModule(Module module, int uid) {
        if (module.file == null || module.file.legacy) {
            return;
        }
        var key = new ModuleBinderKey(module.packageName, uid);
        if (sentBinderSet.contains(key) || !sendingBinderSet.add(key)) {
            return;
        }
        try {
            LSPModuleService service;
            synchronized (serviceMap) {
                service = serviceMap.computeIfAbsent(module, LSPModuleService::new);
            }
            binderExecutor.execute(() -> service.sendBinder(uid, key));
        } catch (Throwable e) {
            sendingBinderSet.remove(key);
            Log.w(TAG, "failed to schedule module binder for uid " + uid, e);
        }
    }

    private void sendBinder(int uid, ModuleBinderKey key) {
        var name = loadedModule.packageName;
        try {
            int userId = uid / PackageService.PER_USER_RANGE;
            if (!ConfigManager.getInstance().isModuleEnabledForUser(name, userId)) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    DeviceIdleService.addPowerSaveTempWhitelistApp(name, userId, "shell");
                    Log.d(TAG, "add " + userId + ":" + name + " to power save temp whitelist for 30s");
                    try {
                        Thread.sleep(400L);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "sendBinder interrupted while waiting for whitelist, continuing for " + name, e);
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "failed to add " + userId + ":" + name + " to power save temp whitelist", e);
                }
            }
            var authority = name + AUTHORITY_SUFFIX;
            var provider = ActivityManagerService.getContentProvider(authority, userId);
            for (int attempt = 1; provider == null && attempt < 3; attempt++) {
                Log.d(TAG, "no service provider for " + name + ", attempt " + attempt);
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Log.d(TAG, "sendBinder interrupted during retry sleep for " + name + ", continuing", e);
                }
                provider = ActivityManagerService.getContentProvider(authority, userId);
            }
            if (provider == null) {
                Log.d(TAG, "no service provider for " + name + " after 3 attempts");
                return;
            }
            var extra = new Bundle();
            extra.putBinder("binder", asBinder());
            Bundle reply = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                reply = provider.call(new AttributionSource.Builder(1000).setPackageName("android").build(), authority, SEND_BINDER, null, extra);
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                reply = provider.call("android", null, authority, SEND_BINDER, null, extra);
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                reply = provider.call("android", authority, SEND_BINDER, null, extra);
            } else {
                reply = provider.call("android", SEND_BINDER, null, extra);
            }
            if (reply != null) {
                Log.d(TAG, "sent module binder to " + name);
                sentBinderSet.add(key);
            } else {
                Log.w(TAG, "failed to send module binder to " + name);
            }
        } catch (Throwable e) {
            Log.w(TAG, "failed to send module binder for uid " + uid, e);
        } finally {
            sendingBinderSet.remove(key);
        }
    }

    private static final class ModuleBinderKey {
        private final String packageName;
        private final int uid;

        private ModuleBinderKey(String packageName, int uid) {
            this.packageName = packageName;
            this.uid = uid;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ModuleBinderKey)) return false;
            var key = (ModuleBinderKey) o;
            return uid == key.uid && Objects.equals(packageName, key.packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, uid);
        }
    }

    LSPModuleService(@NonNull Module module) {
        loadedModule = module;
    }

    private int ensureModule() throws RemoteException {
        var appId = Binder.getCallingUid() % PER_USER_RANGE;
        if (loadedModule.appId != appId) {
            throw new RemoteException("Module " + loadedModule.packageName + " is not for uid " + Binder.getCallingUid());
        }
        return Binder.getCallingUid() / PER_USER_RANGE;
    }

    // dual-wire dispatch (API 100 vs API 101)
    //
    // the IXposedService AIDL reused the same transaction codes across APIs but
    // slapped different methods on the privilege/properties and scope calls:
    //   6  = getFrameworkPrivilege (int) in 100, getFrameworkProperties (long) in 101
    //   12 = requestScope(String)         in 100, requestScope(List)          in 101
    //   13 = removeScope(String)->String  in 100, removeScope(List)->void     in 101
    // note the codes are the AIDL-declared values + 1: the AGP aidl compiler
    // maps `= N` to FIRST_CALL_TRANSACTION + N, and both the daemon and every
    // module are built with it, so the wire agrees (a module's getScope really
    // arrives as 11). codes 2-5, 11 and 21-33 are type-compatible, so the
    // generated stub keeps handling those. which wire a module speaks comes
    // from module.prop targetApiVersion (API 100 modules don't declare it), so
    // they keep working untouched. tbh idk if this survives the next API bump,
    // but it's the only way to serve both APIs right now.

    private static final int TRANSACTION_GET_FRAMEWORK_PRIVILEGE = 6;
    private static final int TRANSACTION_REQUEST_SCOPE = 12;
    private static final int TRANSACTION_REMOVE_SCOPE = 13;

    private boolean speaksApi101() {
        return loadedModule.file != null
                && loadedModule.file.targetApiVersion >= MIN_SUPPORTED_API_VERSION;
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        switch (code) {
            case TRANSACTION_GET_FRAMEWORK_PRIVILEGE:
                data.enforceInterface(getInterfaceDescriptor());
                if (speaksApi101()) {
                    reply.writeNoException();
                    reply.writeLong(getFrameworkProperties());
                } else {
                    reply.writeNoException();
                    reply.writeInt(getFrameworkPrivilege());
                }
                return true;
            case TRANSACTION_REQUEST_SCOPE:
                data.enforceInterface(getInterfaceDescriptor());
                if (speaksApi101()) {
                    var packages = data.createStringArrayList();
                    var callback = IXposedScopeCallback.Stub.asInterface(data.readStrongBinder());
                    requestScope(packages, callback);
                } else {
                    var packageName = data.readString();
                    var callback = IXposedScopeCallback.Stub.asInterface(data.readStrongBinder());
                    requestScope(packageName, callback);
                }
                return true;
            case TRANSACTION_REMOVE_SCOPE:
                data.enforceInterface(getInterfaceDescriptor());
                if (speaksApi101()) {
                    var packages = data.createStringArrayList();
                    removeScope(packages);
                    reply.writeNoException();
                } else {
                    var packageName = data.readString();
                    reply.writeNoException();
                    reply.writeString(removeScope(packageName));
                }
                return true;
            case TRANSACTION_GET_RUNNING_TARGETS: {
                data.enforceInterface(getInterfaceDescriptor());
                ensureModule();
                var targets = collectRunningTargets();
                reply.writeNoException();
                reply.writeTypedList(targets);
                return true;
            }
            case TRANSACTION_HOT_RELOAD_MODULE: {
                data.enforceInterface(getInterfaceDescriptor());
                var targetId = data.readLong();
                var extras = data.readTypedObject(Bundle.CREATOR);
                var callback = data.readStrongBinder();
                try {
                    submitHotReload(targetId, extras, callback);
                    reply.writeNoException();
                } catch (Exception e) {
                    // A SecurityException for an id that is not this module's, a RemoteException for
                    // a request that was never accepted: both have to reach the caller as an
                    // exception, since the interface promises an id from getRunningTargets works.
                    reply.writeException(e);
                }
                return true;
            }
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    // API 102: running targets

    /**
     * Every live process this module is loaded into, with the state of the module code it is running.
     * Also refreshes the target registry, so ids are allocated for processes that just appeared and
     * dropped once their process is gone.
     */
    @NonNull
    private List<HookedProcess> collectRunningTargets() {
        var packageName = loadedModule.packageName;
        var installedApk = loadedModule.apkPath;
        var result = new ArrayList<HookedProcess>();
        synchronized (targetLock) {
            var live = new HashSet<TargetKey>();
            for (var process : LSPApplicationService.runningProcesses()) {
                if (!LSPApplicationService.runsModule(process, packageName)) {
                    continue;
                }
                var key = new TargetKey(process.uid, process.pid, process.processName);
                live.add(key);
                var targetId = targetIdsByKey.computeIfAbsent(key, k -> ++nextTargetId);

                var service = process.processService;
                String loadedApk = null;
                long loadedVersionCode = -1;
                if (service != null) {
                    try {
                        loadedApk = service.getLoadedModuleApk(packageName);
                        if (loadedApk != null) {
                            loadedVersionCode = service.getLoadedModuleVersionCode(packageName);
                        }
                    } catch (RemoteException e) {
                        // died between being listed and being asked; it drops out of the registry
                        // on the next pass anyway
                        continue;
                    }
                }

                int state;
                if (reloadingTargets.contains(targetId)) {
                    state = HookedProcess.TARGET_STATE_RELOADING;
                } else if (failedTargets.contains(targetId)) {
                    state = HookedProcess.TARGET_STATE_FAILED;
                } else if (loadedApk != null && loadedApk.equals(installedApk)) {
                    // The identity is the APK path, not the version code: an update always installs
                    // under a new path, so this stays right even for a rebuild that reuses the code.
                    state = HookedProcess.TARGET_STATE_UP_TO_DATE;
                } else {
                    state = HookedProcess.TARGET_STATE_STALE;
                }
                result.add(new HookedProcess(targetId, process.uid, process.pid, process.processName,
                        state, loadedVersionCode));
            }
            targetIdsByKey.keySet().retainAll(live);
            targetsById.entrySet().removeIf(entry -> !live.contains(entry.getValue()));
            reloadingTargets.retainAll(targetsById.keySet());
            failedTargets.retainAll(targetsById.keySet());
        }
        return result;
    }

    /**
     * Validates a hot reload request and hands it to the worker. Returns as soon as the request is
     * accepted: the interface says the outcome arrives through the callback, not as a return value.
     */
    private void submitHotReload(long targetId, @Nullable Bundle extras, @Nullable IBinder callback) throws RemoteException {
        ensureModule();
        final TargetKey target;
        final boolean alreadyReloading;
        synchronized (targetLock) {
            target = targetsById.get(targetId);
            if (target == null) {
                throw new SecurityException("Unknown or expired target id " + targetId);
            }
            alreadyReloading = !reloadingTargets.add(targetId);
            if (!alreadyReloading) {
                failedTargets.remove(targetId);
            }
        }
        if (alreadyReloading) {
            // The interface has a status for saying so rather than a second queued request.
            notifyHotReload(callback, HOT_RELOAD_IN_PROGRESS, null);
            return;
        }
        hotReloadExecutor.execute(() -> {
            var outcome = performHotReload(target, extras);
            var status = outcome.getInt(ILSPProcessService.RESULT_STATUS);
            synchronized (targetLock) {
                reloadingTargets.remove(targetId);
                if (status == HOT_RELOAD_FAILED) {
                    failedTargets.add(targetId);
                } else {
                    failedTargets.remove(targetId);
                }
            }
            notifyHotReload(callback, status, outcome.getString(ILSPProcessService.RESULT_MESSAGE));
        });
    }

    @NonNull
    private Bundle performHotReload(@NonNull TargetKey target, @Nullable Bundle extras) {
        var service = LSPApplicationService.processServiceOf(target.uid, target.pid);
        if (service == null) {
            return hotReloadOutcome(HOT_RELOAD_PROCESS_DIED, null);
        }
        try {
            return service.hotReloadModule(loadedModule.packageName, extras);
        } catch (RemoteException e) {
            return hotReloadOutcome(HOT_RELOAD_PROCESS_DIED, null);
        } catch (Throwable t) {
            return hotReloadOutcome(HOT_RELOAD_FAILED, t.getMessage());
        }
    }

    /**
     * The status and diagnostic message a hot reload came back with, in the shape the AIDL carries
     * them. A Bundle rather than a parcelable, so the channel needs nothing more generated than the
     * interface itself.
     */
    @NonNull
    private static Bundle hotReloadOutcome(int status, @Nullable String message) {
        var outcome = new Bundle();
        outcome.putInt(ILSPProcessService.RESULT_STATUS, status);
        outcome.putString(ILSPProcessService.RESULT_MESSAGE, message);
        return outcome;
    }

    /**
     * Reports an outcome on a module app's {@code IHotReloadCallback}.
     *
     * <p>Written out by hand for the same reason the request is read by hand: the pinned AIDL has no
     * such callback, so there is no compiled stub to call. The method is {@code oneway}, so the
     * transaction is fired and forgotten and a dead module app cannot block the worker.
     * </p>
     */
    private static void notifyHotReload(@Nullable IBinder callback, int status, @Nullable String message) {
        if (callback == null) return;
        var data = Parcel.obtain();
        try {
            data.writeInterfaceToken(HOT_RELOAD_CALLBACK_DESCRIPTOR);
            data.writeInt(status);
            data.writeString(message);
            callback.transact(TRANSACTION_ON_HOT_RELOAD_RESULT, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            Log.w(TAG, "failed to report hot reload result", e);
        } finally {
            data.recycle();
        }
    }

    /**
     * Reloads the module in every process it is loaded into. Called when the module APK changed and
     * the module opted into it, so there is no callback: failures still show up as
     * {@code TARGET_STATE_FAILED} on the next {@code getRunningTargets()}.
     */
    void autoReloadRunningTargets() {
        // Refresh first: an update can be the first thing the daemon hears about this module, so
        // nothing may have called getRunningTargets() and allocated the ids yet.
        collectRunningTargets();
        final Map<Long, TargetKey> targets;
        synchronized (targetLock) {
            targets = new HashMap<>(targetsById);
        }
        for (var entry : targets.entrySet()) {
            var targetId = entry.getKey();
            var target = entry.getValue();
            var service = LSPApplicationService.processServiceOf(target.uid, target.pid);
            if (service == null) {
                continue;
            }
            synchronized (targetLock) {
                if (!reloadingTargets.add(targetId)) {
                    continue;
                }
                failedTargets.remove(targetId);
            }
            try {
                var outcome = service.hotReloadModule(loadedModule.packageName, null);
                synchronized (targetLock) {
                    reloadingTargets.remove(targetId);
                    if (outcome.getInt(ILSPProcessService.RESULT_STATUS) == HOT_RELOAD_FAILED) {
                        failedTargets.add(targetId);
                    }
                }
            } catch (Throwable e) {
                // Nothing asked for this reload, so a dead process or a broken module must not be
                // able to take the daemon down with it - it just shows up as a failed target.
                Log.w(TAG, "auto hot reload of " + loadedModule.packageName + " in " + target.processName, e);
                synchronized (targetLock) {
                    reloadingTargets.remove(targetId);
                    failedTargets.add(targetId);
                }
            }
        }
    }

    /**
     * Reloads the running processes of a module that just got updated, when it asked for that.
     *
     * <p>Called from the package-changed path, after the module cache has been refreshed, so the new
     * processes get dex from the build that was just installed.
     * </p>
     */
    static void autoHotReloadModule(@NonNull String packageName) {
        var module = ConfigManager.getInstance().getModuleByPackage(packageName);
        if (module == null || module.file == null) {
            return;
        }
        // Hot reload is an API 102 RPC and it asks the old code to retire itself, so it only happens
        // for modules that are both built for it and opted in.
        if (module.file.targetApiVersion < XposedInterface.API_102 || !module.file.autoHotReload) {
            return;
        }
        LSPModuleService service;
        synchronized (serviceMap) {
            service = serviceMap.get(module);
        }
        if (service != null) {
            Log.d(TAG, "auto hot reloading " + packageName);
            service.autoReloadRunningTargets();
        }
    }

    @Override
    public int getAPIVersion() throws RemoteException {
        ensureModule();
        return XPOSED_API_VERSION;
    }

    @Override
    public String getFrameworkName() throws RemoteException {
        ensureModule();
        return "LSPosed";
    }

    @Override
    public String getFrameworkVersion() throws RemoteException {
        ensureModule();
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public long getFrameworkVersionCode() throws RemoteException {
        ensureModule();
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public int getFrameworkPrivilege() throws RemoteException {
        ensureModule();
        return IXposedService.FRAMEWORK_PRIVILEGE_ROOT;
    }

    // API 101 wire: framework capabilities as a bitmask (XposedInterface#PROP_*)
    long getFrameworkProperties() throws RemoteException {
        ensureModule();
        var properties = XposedInterface.PROP_CAP_SYSTEM | XposedInterface.PROP_CAP_REMOTE;
        if (ConfigManager.getInstance().dexObfuscate()) {
            properties |= XposedInterface.PROP_RT_API_PROTECTION;
        }
        return properties;
    }

    @Override
    public List<String> getScope() throws RemoteException {
        ensureModule();
        ArrayList<String> res = new ArrayList<>();
        var scope = ConfigManager.getInstance().getModuleScope(loadedModule.packageName);
        if (scope == null) return res;
        for (var s : scope) {
            res.add(s.packageName);
        }
        return res;
    }

    @Override
    public void requestScope(String packageName, IXposedScopeCallback callback) throws RemoteException {
        var userId = ensureModule();
        if (ConfigManager.getInstance().scopeRequestBlocked(loadedModule.packageName, userId)) {
            callback.onScopeRequestDenied(packageName);
        } else {
            LSPNotificationManager.requestModuleScope(loadedModule.packageName, userId, packageName, callback, false);
            callback.onScopeRequestPrompted(packageName);
        }
    }

    // API 101 wire: bulk scope request. reuses the per-package notification flow of
    // API 100, only the callback delivery differs (onScopeRequestApproved(List) /
    // onScopeRequestFailed(String)). could've ported irena's bulk notification, but
    // that'd mean rewriting the whole intent plumbing for little gain.
    void requestScope(List<String> packages, IXposedScopeCallback callback) throws RemoteException {
        Objects.requireNonNull(packages, "packages cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");
        var userId = ensureModule();
        if (packages.isEmpty()) {
            LSPNotificationManager.notifyScopeRequestFailed(callback, true, null, "Invalid request");
            return;
        }
        if (ConfigManager.getInstance().scopeRequestBlocked(loadedModule.packageName, userId)) {
            LSPNotificationManager.notifyScopeRequestFailed(callback, true, null, "Blocked by user");
            return;
        }
        for (var packageName : packages) {
            LSPNotificationManager.requestModuleScope(loadedModule.packageName, userId, packageName, callback, true);
        }
    }

    @Override
    public String removeScope(String packageName) throws RemoteException {
        var userId = ensureModule();
        try {
            if (!ConfigManager.getInstance().removeModuleScope(loadedModule.packageName, packageName, userId)) {
                return "Invalid request";
            }
            return null;
        } catch (Throwable e) {
            return e.getMessage();
        }
    }

    // API 101 wire: bulk scope removal. the wire returns void, so errors come back
    // as a RemoteException instead of an error string.
    void removeScope(List<String> packages) throws RemoteException {
        Objects.requireNonNull(packages, "packages cannot be null");
        var userId = ensureModule();
        for (var packageName : packages) {
            try {
                if (!ConfigManager.getInstance().removeModuleScope(loadedModule.packageName, packageName, userId)) {
                    throw new RemoteException("Invalid request");
                }
            } catch (RemoteException remote) {
                throw remote;
            } catch (Throwable e) {
                var re = new RemoteException(e.getMessage());
                re.initCause(e);
                throw re;
            }
        }
    }

    @Override
    public Bundle requestRemotePreferences(String group) throws RemoteException {
        var userId = ensureModule();
        var bundle = new Bundle();
        bundle.putSerializable("map", ConfigManager.getInstance().getModulePrefs(loadedModule.packageName, userId, group));
        return bundle;
    }

    @Override
    public void updateRemotePreferences(String group, Bundle diff) throws RemoteException {
        var userId = ensureModule();
        Map<String, Object> values = new ArrayMap<>();
        if (diff.containsKey("delete")) {
            var deletes = (Set<?>) diff.getSerializable("delete");
            for (var key : deletes) {
                values.put((String) key, null);
            }
        }
        if (diff.containsKey("put")) {
            try {
                var puts = (Map<?, ?>) diff.getSerializable("put");
                for (var entry : puts.entrySet()) {
                    values.put((String) entry.getKey(), entry.getValue());
                }
            } catch (Throwable e) {
                Log.e(TAG, "updateRemotePreferences: ", e);
            }
        }
        try {
            ConfigManager.getInstance().updateModulePrefs(loadedModule.packageName, userId, group, values);
            ((LSPInjectedModuleService) loadedModule.service).onUpdateRemotePreferences(userId, group, diff);
        } catch (Throwable e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public void deleteRemotePreferences(String group) throws RemoteException {
        var userId = ensureModule();
        ConfigManager.getInstance().deleteModulePrefs(loadedModule.packageName, userId, group);
    }

    @Override
    public String[] listRemoteFiles() throws RemoteException {
        var userId = ensureModule();
        try {
            var dir = ConfigFileManager.resolveModuleDir(loadedModule.packageName, FILES_DIR, userId, Binder.getCallingUid());
            var files = dir.toFile().list();
            return files == null ? new String[0] : files;
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String path) throws RemoteException {
        var userId = ensureModule();
        ConfigFileManager.ensureModuleFilePath(path);
        try {
            var dir = ConfigFileManager.resolveModuleDir(loadedModule.packageName, FILES_DIR, userId, Binder.getCallingUid());
            return ParcelFileDescriptor.open(dir.resolve(path).toFile(), ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE);
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public boolean deleteRemoteFile(String path) throws RemoteException {
        var userId = ensureModule();
        ConfigFileManager.ensureModuleFilePath(path);
        try {
            var dir = ConfigFileManager.resolveModuleDir(loadedModule.packageName, FILES_DIR, userId, Binder.getCallingUid());
            return dir.resolve(path).toFile().delete();
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }
}
