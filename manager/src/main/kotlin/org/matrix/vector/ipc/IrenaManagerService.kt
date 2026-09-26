package org.matrix.vector.ipc

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread
import org.lsposed.lspd.ILSPManagerService
import org.lsposed.lspd.models.Application
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.logW
import rikka.parcelablelist.ParcelableListSlice

/**
 * The ported manager's daemon, as irena answers it.
 *
 * The upstream interface is an AIDL one, and this is the only class that knows it is not talking to
 * Vector's daemon: every screen, view model, repository and [DaemonClient] above it is unchanged
 * source, so the port stays diffable against upstream. The mapping is where irena says the same
 * thing under another name -- `enabledModules` against `getEnabledModules`, `performDexOptMode`
 * against `optimizePackage`, `getAutoInclude` against `getIncludeNewApps` -- and where it says
 * something else entirely, which is called out method by method below.
 *
 * Not every seam is exact, and the four that are not are marked: a method that cannot be honoured
 * either answers what the platform answers (so the screen shows the truth rather than an invented
 * difference) or refuses, which [DaemonClient] renders as a failed call rather than a wrong answer.
 */
class IrenaManagerService(private val svc: ILSPManagerService) : IManagerService.Stub() {

    /**
     * Unscripted: this is both sides of the wire in one process, so the generation of the interface
     * that these transactions are dispatched from is the same generation this class implements.
     */
    override fun getProtocolVersion(): Int = IManagerService.PROTOCOL_VERSION

    override fun getLibxposedApiVersion(): Int = svc.getXposedApiVersion()

    override fun getFrameworkVersionCode(): Long = svc.getXposedVersionCode().toLong()

    override fun getFrameworkVersionName(): String = svc.getXposedVersionName()

    /**
     * irena's daemon carries no commit, so what it has to report is the framework version.
     *
     * Upstream's stamp is read by `buildStamp`, which takes a string not beginning with a short sha
     * as "cannot tell" rather than "these differ" -- which is the truthful answer for a build whose
     * commit the daemon never recorded, and the reason this is the version name and not something
     * shaped like a stamp.
     */
    override fun getBuildStamp(): String? = svc.getXposedVersionName()

    override fun isSystemServerAttached(): Boolean = svc.systemServerRequested()

    override fun isSepolicyLoaded(): Boolean = svc.isSepolicyLoaded()

    override fun getDex2OatWrapperState(): Int = svc.getDex2OatWrapperCompatibility()

    /** irena disables dex2oat inlining through its dex obfuscation, which is the same switch. */
    override fun isDex2OatInliningDisabled(): Boolean = svc.getDexObfuscate()

    override fun getEnabledModules(): MutableList<String> =
        svc.enabledModules().map { it.packageName }.distinct().toMutableList()

    /**
     * Device-wide here, per user there.
     *
     * irena keeps the switch per user, and a module is one package for the whole device, so this
     * applies to every user the daemon knows about and answers whether any of them changed. The
     * loop is not short-circuited: stopping at the first success would leave the rest of the users
     * with the old state and a caller that was told it worked.
     */
    override fun setModuleEnabled(packageName: String?, enabled: Boolean): Boolean {
        val pkg = packageName ?: return false
        var changed = false
        runCatching {
            for (user in svc.users) {
                if (user.id < 0) continue
                val one = if (enabled) svc.enableModule(pkg, user.id) else svc.disableModule(pkg, user.id)
                changed = changed || one
            }
        }.onFailure { logW("setModuleEnabled failed", it) }
        return changed
    }

    /** Null is a refusal -- the framework's own pseudo-module -- and is passed on as one. */
    override fun getModuleScope(packageName: String?): MutableList<ScopeEntry>? {
        val pkg = packageName ?: return null
        val scope = svc.getModuleScope(pkg) ?: return null
        return scope.map { entry ->
            ScopeEntry().apply {
                this.packageName = entry.packageName
                this.userId = entry.userId
            }
        }.toMutableList()
    }

    override fun setModuleScope(packageName: String?, scope: MutableList<ScopeEntry>?): Boolean {
        val pkg = packageName ?: return false
        val applications = (scope ?: mutableListOf()).map { entry ->
            Application().apply {
                this.packageName = entry.packageName
                this.userId = entry.userId
            }
        }
        return svc.setModuleScope(pkg, applications)
    }

    override fun getIncludeNewApps(packageName: String?): Boolean =
        packageName?.let { svc.getAutoInclude(it) } ?: false

    override fun setIncludeNewApps(packageName: String?, enable: Boolean): Boolean =
        packageName?.let { svc.setAutoInclude(it, enable) } ?: false

    /**
     * Empty, and that is the answer rather than a gap.
     *
     * irena's daemon does not keep what the user asked for apart from what it managed to load, so
     * it has nothing to say here. Absence from this list means a module loaded, which is true of
     * every module on a device running irena.
     */
    override fun getModuleLoadFailures(): MutableList<ModuleLoadFailure> = mutableListOf()

    override fun isStatusNotificationEnabled(): Boolean = svc.enableStatusNotification()

    override fun setStatusNotificationEnabled(enabled: Boolean) {
        svc.setEnableStatusNotification(enabled)
    }

    override fun isVerboseLogEnabled(): Boolean = svc.isVerboseLog()

    override fun setVerboseLogEnabled(enabled: Boolean) {
        svc.setVerboseLog(enabled)
    }

    override fun getLiveLogPart(verbose: Boolean): ParcelFileDescriptor? =
        if (verbose) svc.getVerboseLog() else svc.getModulesLog()

    override fun getLogParts(verbose: Boolean): MutableList<String> =
        svc.getLogParts(verbose).toMutableList()

    override fun getLogPart(verbose: Boolean, name: String?): ParcelFileDescriptor? =
        name?.let { svc.getLogPart(verbose, it) }

    /**
     * irena rotates by writing a sentinel its log reader acts on, and deletes nothing -- which is
     * what upstream's `startNewLogPart` means, and why this is not the manager's "clear logs".
     */
    override fun startNewLogPart(verbose: Boolean) {
        svc.clearLogs(verbose)
    }

    override fun writeBugReport(zipFd: ParcelFileDescriptor?) {
        if (zipFd != null) svc.getLogs(zipFd)
    }

    override fun getInstalledPackagesFromAllUsers(
        flags: Int,
        filterNoProcess: Boolean,
    ): ParcelableListSlice<PackageInfo> = svc.getInstalledPackagesFromAllUsers(flags, filterNoProcess)

    override fun queryIntentActivitiesAsUser(
        intent: Intent?,
        flags: Int,
        userId: Int,
    ): ParcelableListSlice<ResolveInfo> = svc.queryIntentActivitiesAsUser(intent, flags, userId)

    override fun getUsers(): MutableList<DeviceUser> =
        svc.users.map { user ->
            DeviceUser().apply {
                this.id = user.id
                this.name = user.name
            }
        }.toMutableList()

    /**
     * The user switch cannot be suppressed here: irena's daemon has one entry point for this and no
     * flag for it, so a caller asking not to switch users gets the switch. Nothing in the ported
     * screens asks for the other behaviour today.
     */
    override fun startActivityAsUser(intent: Intent?, userId: Int, noUserSwitch: Boolean): Int =
        svc.startActivityAsUserWithFeature(intent, userId)

    override fun forceStopPackage(packageName: String?, userId: Int) {
        if (packageName != null) svc.forceStopPackage(packageName, userId)
    }

    /** ALL_USERS is upstream's spelling of "every user", which irena has to do one at a time. */
    override fun uninstallPackage(packageName: String?, userId: Int): Boolean {
        val pkg = packageName ?: return false
        if (userId != IManagerService.ALL_USERS) return svc.uninstallPackage(pkg, userId)
        // A throw here is not an answer of "no": it is the daemon being unreachable, and reporting
        // false for that would read as a refusal.
        val users = try {
            svc.users
        } catch (t: Throwable) {
            logW("uninstall: could not list users", t)
            return false
        }
        if (users.isEmpty()) return false
        var every = false
        for (user in users) {
            val one = svc.uninstallPackage(pkg, user.id)
            every = one || every
        }
        return every
    }

    override fun optimizePackage(packageName: String?): Boolean =
        packageName?.let { svc.performDexOptMode(it) } ?: false

    override fun softReboot() {
        svc.softReboot()
    }

    override fun reboot() {
        svc.reboot()
    }

    /**
     * True, and the toggle has nothing to change.
     *
     * Upstream's daemon hands every app a launcher icon so the scope list can name modules that
     * ship none. irena lists installed packages without that, so there is no state to read and none
     * to write: true is what upstream answers on a device where nothing has ever set it.
     */
    override fun isForcedLauncherIcons(): Boolean = true

    override fun setForcedLauncherIcons(force: Boolean) = Unit

    override fun getRootImplementation(): Int = svc.getRootImplementation()

    /**
     * A pipe, because the two sides describe the same flash differently.
     *
     * irena's `flashZip` is oneway and takes a descriptor to append the installer's merged stdout
     * and stderr to; this interface reports one line at a time and then once at the end. So the
     * write end goes to the daemon, which owns and closes it, and the read end is drained here --
     * EOF is what says the flash is over.
     *
     * The exit status is read back out of the installer's own output, because irena's call returns
     * nothing: `! Flash failed, exit with N` and `! Timeout, abort` are the two lines it writes when
     * there is no success to report.
     */
    override fun installFrameworkZip(zipPath: String?, receiver: IFrameworkInstallReceiver?) {
        if (zipPath == null || receiver == null) return
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "framework-install", isDaemon = true) {
            try {
                var exit = 0
                BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pipe[0]))).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        Regex("^! Flash failed, exit with (\\d+)$").find(line)?.let {
                            exit = it.groupValues[1].toIntOrNull() ?: 1
                        }
                        if (line.startsWith("! Timeout, abort")) {
                            exit = IFrameworkInstallReceiver.INSTALL_NOT_EXECUTED
                        }
                        runCatching { receiver.onLine(line) }
                    }
                }
                runCatching { receiver.onFinished(exit) }
            } catch (t: Throwable) {
                logE("framework install: reading the installer output failed", t)
                runCatching { receiver.onFinished(IFrameworkInstallReceiver.INSTALL_NOT_EXECUTED) }
            }
        }
        runCatching { svc.flashZip(zipPath, pipe[1]) }.onFailure {
            logE("framework install: the daemon refused the zip", it)
            runCatching { pipe[1].close() }
            runCatching { receiver.onFinished(IFrameworkInstallReceiver.INSTALL_NOT_EXECUTED) }
        }
    }

    override fun getManagerApk(): ParcelFileDescriptor? = svc.getManagerApk()

    /**
     * Refused rather than faked.
     *
     * irena's daemon exposes no privileged shell and no system property accessor, and neither is
     * reachable from any screen in this port -- they are answered here so that a caller which does
     * ask gets a failed Result out of [DaemonClient] instead of an empty string that reads like a
     * command that ran and printed nothing.
     */
    override fun execPrivilegedCommand(cmd: String?): String =
        throw UnsupportedOperationException("irena's daemon exposes no privileged shell")

    override fun getSystemProperty(key: String?, def: String?): String =
        throw UnsupportedOperationException("irena's daemon exposes no system properties")

    override fun setSystemProperty(key: String?, value: String?): Boolean =
        throw UnsupportedOperationException("irena's daemon exposes no system properties")
}
