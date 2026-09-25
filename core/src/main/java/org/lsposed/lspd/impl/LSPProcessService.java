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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lsposed.lspd.service.ILSPProcessService;

/**
 * The injected process' half of the daemon &lt;-&gt; process channel (API 102).
 *
 * <p>One instance per process, registered with the daemon right after the process obtained its
 * application service, so the daemon can ask this process which module generation it is running and
 * make it reload. Everything here is a thin forward onto {@link LSPHotReload}, which owns the state
 * and the locking.
 * </p>
 */
public class LSPProcessService extends ILSPProcessService.Stub {

    private static final LSPProcessService INSTANCE = new LSPProcessService();

    private LSPProcessService() {
    }

    @NonNull
    public static LSPProcessService getInstance() {
        return INSTANCE;
    }

    @Nullable
    @Override
    public String getLoadedModuleApk(@NonNull String packageName) {
        return LSPHotReload.loadedApkOf(packageName);
    }

    @Override
    public long getLoadedModuleVersionCode(@NonNull String packageName) {
        return LSPHotReload.loadedVersionCodeOf(packageName);
    }

    @NonNull
    @Override
    public Bundle hotReloadModule(@NonNull String packageName, @Nullable Bundle extras) {
        return LSPHotReload.reload(packageName, extras);
    }
}
