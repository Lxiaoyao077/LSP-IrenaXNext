package org.lsposed.lspd.service;

import org.lsposed.lspd.models.Module;

interface ILSPApplicationService {
    /**
     * Registers the calling process' own service, so the daemon can reach back into it. Called once
     * per injected process, right after it obtained this service (API 102).
     */
    void registerProcessService(ILSPProcessService service);

    boolean isLogMuted();

    List<Module> getLegacyModulesList();

    List<Module> getModulesList();

    String getPrefsPath(String packageName);

    ParcelFileDescriptor requestInjectedManagerBinder(out List<IBinder> binder);
}
