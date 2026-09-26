package org.lsposed.manager

import android.os.IBinder

/**
 * The class irena's daemon reaches for by name.
 *
 * `magisk-loader`'s ParasiticManagerHooker loads `"<managerPackage>.Constants"` out of the injected
 * dex and calls the static `setBinder(IBinder)` on it, with no call site anywhere in this APK --
 * which is why the keep rule in `proguard-rules.pro` exists as well. This APK is the manager, so the
 * manager package is where that class has to be, and it forwards: the ported manager keeps its own
 * Constants where upstream put it, and a future resync with upstream does not have to know about
 * this file.
 */
object Constants {
    @JvmStatic
    fun setBinder(binder: IBinder): Boolean = org.matrix.vector.manager.Constants.setBinder(binder)
}
