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
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.lspd.hooker;


import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.AppComponentFactory;
import android.app.LoadedApk;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import androidx.annotation.NonNull;

import org.lsposed.lspd.impl.LSPosedContext;
import org.lsposed.lspd.util.Hookers;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

@SuppressLint("BlockedPrivateApi")
public class LoadedApkCreateCLHooker implements XposedInterface.Hooker {
    private final static Field defaultClassLoaderField;

    private final static Set<LoadedApk> loadedApks = ConcurrentHashMap.newKeySet();

    static {
        Field field = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                field = LoadedApk.class.getDeclaredField("mDefaultClassLoader");
                field.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
        defaultClassLoaderField = field;
    }

    static void addLoadedApk(LoadedApk loadedApk) {
        loadedApks.add(loadedApk);
    }

    public static void after(XposedInterface.AfterHookCallback callback) {
        LoadedApk loadedApk = (LoadedApk) callback.getThisObject();

        if (callback.getArgs()[0] != null || !loadedApks.contains(loadedApk)) {
            return;
        }

        try {
            Hookers.logD("LoadedApk#createClassLoader starts");

            String packageName = ActivityThread.currentPackageName();
            String processName = ActivityThread.currentProcessName();
            boolean isFirstPackage = packageName != null && processName != null && packageName.equals(loadedApk.getPackageName());
            if (!isFirstPackage) {
                packageName = loadedApk.getPackageName();
                processName = ActivityThread.currentPackageName();
            } else if (packageName.equals("android")) {
                packageName = "system";
            }

            Object mAppDir = XposedHelpers.getObjectField(loadedApk, "mAppDir");
            ClassLoader classLoader = (ClassLoader) XposedHelpers.getObjectField(loadedApk, "mClassLoader");
            Hookers.logD("LoadedApk#createClassLoader ends: " + mAppDir + " -> " + classLoader);

            if (classLoader == null) {
                return;
            }

            if (!isFirstPackage && !XposedHelpers.getBooleanField(loadedApk, "mIncludeCode")) {
                Hookers.logD("LoadedApk#<init> mIncludeCode == false: " + mAppDir);
                return;
            }

            if (!isFirstPackage && !XposedInit.getLoadedModules().getOrDefault(packageName, Optional.of("")).isPresent()) {
                return;
            }

            var param = new PackageLoadParam(loadedApk, isFirstPackage);
            param.setClassLoader(classLoader);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                param.setAppComponentFactory((AppComponentFactory) XposedHelpers.getObjectField(loadedApk, "mAppComponentFactory"));
            }

            XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(
                    XposedBridge.sLoadedPackageCallbacks);
            lpparam.packageName = packageName;
            lpparam.processName = processName;
            lpparam.classLoader = classLoader;
            lpparam.appInfo = loadedApk.getApplicationInfo();
            lpparam.isFirstApplication = isFirstPackage;

            Hookers.logD("Call handleLoadedPackage: packageName=" + lpparam.packageName + " processName=" + lpparam.processName + " isFirstPackage=" + isFirstPackage + " classLoader=" + lpparam.classLoader + " appInfo=" + lpparam.appInfo);
            XC_LoadPackage.callAll(lpparam);

            // fire every lifecycle callback and let each module ignore what it didn't
            // override. API 100 modules get onPackageLoaded, API 101 modules get
            // onPackageLoaded + onPackageReady (PackageReadyParam extends PackageLoadedParam,
            // so one param instance serves both). tbh idk if it's the cleanest, but it keeps
            // API 100 modules alive without a whole compat layer.
            LSPosedContext.callOnPackageLoaded(param);
            LSPosedContext.callOnPackageReady(param);
        } catch (Throwable t) {
            Hookers.logE("error when hooking LoadedApk#createClassLoader", t);
        } finally {
            loadedApks.remove(loadedApk);
        }
    }

    static class PackageLoadParam implements XposedModuleInterface.PackageReadyParam {
        private final LoadedApk loadedApk;
        private final boolean isFirstPackage;
        private ClassLoader classLoader;
        private AppComponentFactory appComponentFactory;

        PackageLoadParam(LoadedApk loadedApk, boolean isFirstPackage) {
            this.loadedApk = loadedApk;
            this.isFirstPackage = isFirstPackage;
        }

        void setClassLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        void setAppComponentFactory(AppComponentFactory appComponentFactory) {
            this.appComponentFactory = appComponentFactory;
        }

        @NonNull
        @Override
        public String getPackageName() {
            return loadedApk.getPackageName();
        }

        @NonNull
        @Override
        public ApplicationInfo getApplicationInfo() {
            return loadedApk.getApplicationInfo();
        }

        @NonNull
        @Override
        public ClassLoader getDefaultClassLoader() {
            if (defaultClassLoaderField == null) {
                // mDefaultClassLoader is API 29+ only, so fall back to the package classloader. iirc it's good enough for hooking
                return classLoader;
            }
            try {
                ClassLoader defaultClassLoader = (ClassLoader) defaultClassLoaderField.get(loadedApk);
                if (defaultClassLoader == null) {
                    throw new IllegalStateException("Default ClassLoader is not ready");
                }
                return defaultClassLoader;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        @NonNull
        @Override
        public ClassLoader getClassLoader() {
            if (classLoader == null) {
                throw new IllegalStateException("ClassLoader is not ready");
            }
            return classLoader;
        }

        @Override
        public boolean isFirstPackage() {
            return isFirstPackage;
        }

        @NonNull
        @Override
        public AppComponentFactory getAppComponentFactory() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                throw new UnsupportedOperationException();
            }
            if (appComponentFactory != null) {
                return appComponentFactory;
            }
            return (AppComponentFactory) XposedHelpers.getObjectField(loadedApk, "mAppComponentFactory");
        }
    }

}
