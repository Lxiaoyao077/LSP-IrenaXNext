package org.matrix.vector.manager

import android.os.IBinder
import kotlin.system.exitProcess
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.ipc.IrenaManagerService
import org.matrix.vector.manager.di.ServiceLocator

/**
 * The one entry point the framework reaches by reflection.
 *
 * `ParasiticManagerHooker.sendBinderToManager` loads `<managerPackage>.Constants` out of the
 * injected dex and invokes the static `setBinder(IBinder)` below. Nothing inside this APK calls it,
 * so R8 must be told to keep both — see `proguard-rules.pro`. Renaming this class or the method
 * breaks the handshake silently, at runtime, with no compile error anywhere.
 */
object Constants {
    /**
     * The only tag the manager logs under, and it is not arbitrary.
     *
     * `logcat.cpp` routes any tag beginning `Vector` into the daemon's **verbose** stream, so with
     * verbose logging on everything logged here appears in the Verbose tab beside the daemon's own
     * lines and travels in the zip export — the place a reader already looks. A file-local tag
     * would be ordinary Android practice and would land nowhere; there are none in this app.
     *
     * Nothing logs with it directly. [logE], [logW] and [logI] hold it, and the conventions for
     * what a message says and which level it says it at are documented on them.
     */
    const val TAG = "VectorManager"

    @JvmStatic
    fun setBinder(binder: IBinder): Boolean {
        // The binder that arrives is irena's -- the daemon reaches this class by reflection and
        // hands over an ILSPManagerService -- while this APK's screens are written against the
        // IManagerService they were ported with. IrenaManagerService is the seam between them, and
        // it is local: nothing here crosses a process boundary except the one call it forwards.
        //
        // The descriptor is still checked, for the reason it was checked upstream: Stub.asInterface
        // wraps any binder in a proxy without checking, the binder stays alive so isBinderAlive()
        // keeps answering true, and every transaction then throws out of the daemon's
        // enforceInterface -- which DaemonClient turns into a failed Result and every screen draws
        // as empty. Asking costs one transaction and is exempt from the version check by
        // construction, since INTERFACE_TRANSACTION sits outside the band the dispatcher checks a
        // token for.
        val expected = "org.lsposed.lspd.ILSPManagerService"
        val theirDescriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
        if (theirDescriptor != null && theirDescriptor != expected) {
            logE(
                "ipc: the daemon speaks $theirDescriptor, this manager speaks $expected; " +
                    "refusing to bind"
            )
            ServiceLocator.bindMismatch(theirDescriptor)
            return false
        }

        val daemon = ILSPManagerService.Stub.asInterface(binder)

        // No protocol handshake: the manager and the daemon ship in the same module and are
        // flashed together, so there is no revision skew to detect here the way there was with
        // two separately published artifacts.
        ServiceLocator.bind(IrenaManagerService(daemon))

        try {
            // If the daemon dies the manager is holding a dead binder and every screen would
            // silently show empty state, which reads as "you have no modules" rather than "the
            // framework is gone". Exiting is blunt but honest.
            binder.linkToDeath(
                {
                    logW("ipc: daemon binder died, manager exiting")
                    exitProcess(0)
                },
                0,
            )
        } catch (e: Exception) {
            logE("ipc: linkToDeath on the daemon binder failed, exiting the manager process", e)
            exitProcess(0)
        }

        return binder.isBinderAlive
    }
}
