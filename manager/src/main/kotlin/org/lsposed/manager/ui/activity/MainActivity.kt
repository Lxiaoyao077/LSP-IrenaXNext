package org.lsposed.manager.ui.activity

/**
 * The class name irena's framework launches.
 *
 * `magisk-loader`'s ParasiticManagerHooker rewrites every launch intent it intercepts to
 * `"org.lsposed.manager.ui.activity.MainActivity"` and picks that activity's ActivityInfo out of
 * the manager APK by the same literal. Neither is derived from anything this APK declares, so the
 * manager is injected, the process is started, and no window appears unless a class of exactly
 * this name exists -- and is not renamed by R8, which is why proguard-rules.pro keeps it.
 *
 * The ported manager's own entry point stays where upstream put it, and this forwards. Upstream has
 * the same seam under its own host package name (org.javsaia.vector.manager.ui.MainActivity), which
 * is what showed that forwarding rather than renaming is the shape to keep: the class the host
 * names is the host's business, and a future resync with upstream does not have to know about it.
 */
class MainActivity : org.matrix.vector.manager.ui.MainActivity()
