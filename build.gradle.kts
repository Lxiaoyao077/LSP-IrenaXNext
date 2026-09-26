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
 * Copyright (C) 2021 - 2022 LSPosed Contributors
 */

import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.api.AndroidBasePlugin

plugins {
    alias(libs.plugins.lsplugin.cmaker)
    alias(libs.plugins.lsplugin.jgit)
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.agp.app) apply false
    // Pins the Kotlin version on the buildscript classpath. AGP 9 compiles Kotlin itself
    // and applying org.jetbrains.kotlin.android is an error since AGP 9.0, but the compiler
    // version it uses is taken from whatever KGP is on that classpath -- which the Compose
    // stack in :manager-ui needs to be 2.4.10, since its artifacts ship class metadata an
    // older compiler refuses to read.
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.nav.safeargs) apply false
}

cmaker {
    default {
        arguments.addAll(
            arrayOf(
                "-DEXTERNAL_ROOT=${File(rootDir.absolutePath, "external")}",
            )
        )
        val flags = arrayOf(
            "-DINJECTED_AID=$injectedPackageUid",
            "-Wno-gnu-string-literal-operator-template",
            "-Wno-c++2b-extensions",
        )
        cFlags.addAll(flags)
        cppFlags.addAll(flags)
        abiFilters("arm64-v8a", "armeabi-v7a")
    }
    buildTypes {
        if (it.name == "release") {
            arguments += "-DDEBUG_SYMBOLS_PATH=${
                layout.buildDirectory.dir("symbols").get().asFile.absolutePath
            }"
        }
    }
}

val repo = jgit.repo()
val commitCount = (repo?.commitCount("HEAD") ?: 1) + 4200
val latestTag = repo?.latestTag?.removePrefix("v")?.substringBefore("-") ?: "2.0.1"

val injectedPackageName by extra("com.android.shell")
val injectedPackageUid by extra(2000)

val defaultManagerPackageName by extra("org.lsposed.manager")
val verCode by extra(commitCount)
val verName by extra(latestTag)
val androidTargetSdkVersion by extra(36)
val androidMinSdkVersion by extra(29)
// 37 because glide 5.0.9 declares it needs it; its AAR metadata check fails the build on 36.
// compileSdkMinor defaults to 0, which resolves to platforms;android-37.0 - there is no bare
// android-37 package.
val androidBuildToolsVersion by extra("37.0.0")
val androidCompileSdkVersion by extra(37)
val androidCompileNdkVersion by extra(libs.versions.ndk.get())
val androidSourceCompatibility by extra(JavaVersion.VERSION_21)
val androidTargetCompatibility by extra(JavaVersion.VERSION_21)
val androidCmakeVersion by extra("3.28.0+")

tasks.register("Delete", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

subprojects {
    plugins.withType(AndroidBasePlugin::class.java) {
        extensions.configure(CommonExtension::class.java) {
            compileSdk = androidCompileSdkVersion
            ndkVersion = androidCompileNdkVersion
            buildToolsVersion = androidBuildToolsVersion

            externalNativeBuild.cmake.version = androidCmakeVersion

            defaultConfig.minSdk = androidMinSdkVersion
            val applicationDefaultConfig = defaultConfig as? ApplicationDefaultConfig
            if (applicationDefaultConfig != null) {
                applicationDefaultConfig.targetSdk = androidTargetSdkVersion
                applicationDefaultConfig.versionCode = verCode
                applicationDefaultConfig.versionName = verName
            }

            lint.abortOnError = true
            lint.checkReleaseBuilds = false

            compileOptions.sourceCompatibility = androidSourceCompatibility
            compileOptions.targetCompatibility = androidTargetCompatibility
        }
    }
    plugins.withType(JavaPlugin::class.java) {
        extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = androidSourceCompatibility
            targetCompatibility = androidTargetCompatibility
        }
    }
}
