package org.lsposed.lspd.models;

parcelable PreLoadedApk {
    List<SharedMemory> preLoadedDexes;
    List<String> moduleClassNames;
    List<String> moduleLibraryNames;
    boolean legacy;
    boolean exceptionPassthrough;
    int targetApiVersion;

    /**
     * Version code of the module package this APK was read from, as reported to the module so it
     * can name the generation it is running (API 102). Diagnostic only.
     */
    long versionCode;

    /**
     * Whether {@code module.prop} opted into reloading running processes when the module is
     * updated (API 102).
     */
    boolean autoHotReload;
}
