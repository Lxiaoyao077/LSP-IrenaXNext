package org.lsposed.lspd.models;

/**
 * Outcome of an in-process hot reload, as reported by {@code ILSPProcessService}.
 *
 * <p>This is the daemon-side carrier; the module-facing equivalent lives in
 * {@code io.github.libxposed.service}. {@code status} uses the raw
 * {@code HOT_RELOAD_*} codes of the API 102 {@code IXposedService} AIDL, and a {@code null}
 * message on a failure means the old module refused the reload rather than that it broke.
 * </p>
 */
parcelable HotReloadResult {
    int status;
    @nullable String message;
}
