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
 */

package org.lsposed.lspd.impl;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lsposed.lspd.core.ApplicationServiceClient;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPProcessService;
import org.lsposed.lspd.util.LspModuleClassLoader;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hot reload (API 102) bookkeeping for the module generations loaded into this process.
 *
 * <p>A generation is the module code a process is currently running: its class loader, the entry
 * class instances built from it, and the APK it was read from. Hot reloading replaces that whole
 * tuple with a new one read from the updated APK, keeping the process alive. The old generation is
 * asked first and may refuse; the new generation learns what it replaced through
 * {@link XposedModuleInterface.HotReloadedParam#getOldHookHandles()}.
 * </p>
 */
final class LSPHotReload {

    private static final String TAG = "LSPosed-HotReload";

    // Mirrors the raw HOT_RELOAD_* codes the API 102 IXposedService AIDL puts on the wire; declared
    // once in our own AIDL so the daemon, which forwards them, cannot drift apart from here.
    static final int STATUS_SUCCEEDED = ILSPProcessService.HOT_RELOAD_SUCCEEDED;
    static final int STATUS_FAILED = ILSPProcessService.HOT_RELOAD_FAILED;
    static final int STATUS_UNSUPPORTED = ILSPProcessService.HOT_RELOAD_UNSUPPORTED;
    static final int STATUS_IN_PROGRESS = ILSPProcessService.HOT_RELOAD_IN_PROGRESS;
    static final int STATUS_PROCESS_DIED = ILSPProcessService.HOT_RELOAD_PROCESS_DIED;

    /** The module code a process is running, as one unit. */
    static final class Generation {
        final String packageName;
        /** The APK the code was read from. A new install always lands on a new path. */
        final String apk;
        /** Kept strongly reachable: the generation's hooks still point into these classes. */
        final ClassLoader classLoader;
        final List<XposedModule> modules;
        final long versionCode;

        Generation(String packageName, String apk, ClassLoader classLoader,
                   List<XposedModule> modules, long versionCode) {
            this.packageName = packageName;
            this.apk = apk;
            this.classLoader = classLoader;
            this.modules = modules;
            this.versionCode = versionCode;
        }
    }

    private static final Map<String, Generation> generations = new ConcurrentHashMap<>();

    /**
     * Held across the whole reload. Two reloads of the same module, or a reload racing the load of
     * another module, would otherwise interleave generation retirement with hook installation.
     */
    private static final Object reloadLock = new Object();

    private LSPHotReload() {
    }

    static void register(@NonNull Generation generation) {
        generations.put(generation.packageName, generation);
    }

    /** The APK this process loaded {@code packageName} from, or null when it did not load it. */
    @Nullable
    static String loadedApkOf(@NonNull String packageName) {
        var generation = generations.get(packageName);
        return generation == null ? null : generation.apk;
    }

    /** The version code this process loaded {@code packageName} at, or -1. Diagnostic only. */
    static long loadedVersionCodeOf(@NonNull String packageName) {
        var generation = generations.get(packageName);
        return generation == null ? -1 : generation.versionCode;
    }

    /**
     * Replaces the module generation this process is running with one read from the module's current
     * APK.
     *
     * <p>Runs on the binder thread the daemon called in on, and serialises against itself. The
     * sequence is: let the old code veto, snapshot its hook handles, build the new code, retire the
     * old entry instances, then hand the snapshot to the new code. Nothing is retired before the new
     * code exists, so a build failure leaves the process running the old generation untouched.
     * </p>
     *
     * @return a {@link Bundle} carrying {@link ILSPProcessService#RESULT_STATUS} and, when there is
     * one, {@link ILSPProcessService#RESULT_MESSAGE}
     */
    @NonNull
    static Bundle reload(@NonNull String packageName, @Nullable Bundle extras) {
        synchronized (reloadLock) {
            var old = generations.get(packageName);
            if (old == null) {
                Log.w(TAG, "No generation of " + packageName + " in this process to reload");
                return newResult(STATUS_UNSUPPORTED, null);
            }

            // Every entry class of the retiring generation gets to veto. A refusal is a bare "no":
            // the interface spells a module-refused reload as a failure with no message, and only
            // an exception gets a diagnostic one.
            var reloading = new HotReloadingParamImpl(extras);
            for (var module : old.modules) {
                try {
                    if (!module.onHotReloading(reloading)) {
                        Log.d(TAG, packageName + " refused hot reload");
                        return newResult(STATUS_FAILED, null);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "onHotReloading of " + packageName, t);
                    return newResult(STATUS_FAILED, describe(t));
                }
            }

            // Snapshotted before the new code exists, so it cannot pick up hooks the new generation
            // installs while it is being initialised.
            var oldHandles = LSPosedBridge.handlesForModule(packageName);

            // The APK on disk is what changed, so the module has to be described again: the daemon
            // hands out a descriptor carrying dex preloaded from the new build.
            var descriptor = findModuleDescriptor(packageName);
            if (descriptor == null) {
                Log.w(TAG, "The daemon no longer serves " + packageName);
                return newResult(STATUS_UNSUPPORTED, null);
            }

            final List<XposedModule> replacements;
            final ClassLoader classLoader;
            try {
                classLoader = LSPosedContext.createClassLoader(descriptor);
                if (classLoader == null) {
                    return newResult(STATUS_UNSUPPORTED, null);
                }
                descriptor.file.moduleLibraryNames.forEach(org.lsposed.lspd.nativebridge.NativeAPI::recordNativeEntrypoint);
                var context = LSPosedContext.contextOf(descriptor);
                replacements = LSPosedContext.instantiate(classLoader, context, descriptor);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to build a new generation of " + packageName, t);
                return newResult(STATUS_FAILED, describe(t));
            }
            if (replacements.isEmpty()) {
                return newResult(STATUS_UNSUPPORTED, null);
            }

            // Past this point the new code is live, so the old generation is retired first: package
            // lifecycle callbacks must reach the new entry instances and nothing else.
            LSPosedContext.retire(old.modules);
            LSPosedContext.adopt(replacements);
            generations.put(packageName, new Generation(packageName, descriptor.apkPath, classLoader,
                    replacements, descriptor.file.versionCode));

            var reloaded = new HotReloadedParamImpl(extras, reloading.savedState(), oldHandles);
            for (var module : replacements) {
                try {
                    module.onHotReloaded(reloaded);
                } catch (Throwable t) {
                    Log.e(TAG, "onHotReloaded of " + packageName, t);
                }
            }
            Log.d(TAG, "Hot reloaded " + packageName + ", " + oldHandles.size() + " hook handle(s) handed over");
            return newResult(STATUS_SUCCEEDED, null);
        }
    }

    @Nullable
    private static Module findModuleDescriptor(@NonNull String packageName) {
        var client = ApplicationServiceClient.serviceClient;
        if (client == null) return null;
        for (var module : client.getModulesList()) {
            if (packageName.equals(module.packageName)) {
                return module;
            }
        }
        return null;
    }

    /**
     * The status and diagnostic message a reload finished with, in the shape the AIDL carries them.
     *
     * <p>A Bundle rather than a parcelable: the generated AIDL parcelable in this build has no
     * all-args constructor, and the two values are read straight back out on the other side of the
     * channel anyway.
     * </p>
     */
    @NonNull
    private static Bundle newResult(int status, @Nullable String message) {
        var result = new Bundle();
        result.putInt(ILSPProcessService.RESULT_STATUS, status);
        result.putString(ILSPProcessService.RESULT_MESSAGE, message);
        return result;
    }

    private static String describe(Throwable t) {
        var message = t.getMessage();
        return message == null ? t.getClass().getName() : t.getClass().getSimpleName() + ": " + message;
    }

    private static final class HotReloadingParamImpl implements XposedModuleInterface.HotReloadingParam {
        private final Bundle extras;
        private Object savedState;

        HotReloadingParamImpl(Bundle extras) {
            this.extras = extras;
        }

        @Nullable
        @Override
        public Bundle getExtras() {
            return extras;
        }

        @Override
        public void setSavedInstanceState(@Nullable Object outState) {
            // The interface wants state that survives the old generation to be class loader
            // neutral. Only the top level value is checked: walking an arbitrary object graph for
            // stray references would cost more than the leak it would catch, and the module is told
            // exactly what the constraint is.
            if (outState != null && outState.getClass().getClassLoader() instanceof LspModuleClassLoader) {
                throw new IllegalArgumentException("Saved instance state must not be an instance of "
                        + outState.getClass().getName() + ": it is created under the module class loader");
            }
            this.savedState = outState;
        }

        Object savedState() {
            return savedState;
        }
    }

    private static final class HotReloadedParamImpl implements XposedModuleInterface.HotReloadedParam {
        private final Bundle extras;
        private final Object savedState;
        private final List<XposedInterface.HookHandle> oldHandles;

        HotReloadedParamImpl(Bundle extras, Object savedState, List<XposedInterface.HookHandle> oldHandles) {
            this.extras = extras;
            this.savedState = savedState;
            this.oldHandles = oldHandles;
        }

        @Override
        public boolean isSystemServer() {
            return LSPosedContext.isSystemServer;
        }

        @NonNull
        @Override
        public String getProcessName() {
            return LSPosedContext.processName;
        }

        @Nullable
        @Override
        public Bundle getExtras() {
            return extras;
        }

        @Nullable
        @Override
        public Object getSavedInstanceState() {
            return savedState;
        }

        @NonNull
        @Override
        public List<XposedInterface.HookHandle> getOldHookHandles() {
            return Collections.unmodifiableList(oldHandles);
        }
    }
}
