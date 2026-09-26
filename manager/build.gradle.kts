// Ported from MagicModule/VectorXposed-it (GPL-3.0). See the credits in the root README.
//
// Adaptations to irena, kept as close to upstream as the two builds allow:
//   * the Compose plugin comes from the catalog, and Kotlin itself from AGP 9's built-in
//     support (applying org.jetbrains.kotlin.android is an error since AGP 9.0);
//   * ktfmt is dropped, it is upstream's formatter and not a dependency here;
//   * VERSION_HASH comes from the commit CI checked out, because irena's root project
//     exposes no ValueSource for it the way upstream's does;
//   * the launcher icon's monochrome drawable ships in this module instead of adding the
//     daemon's res directory to this source set, which would risk a name collision.
plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        // Material 3 Expressive has not landed in a stable material3 release; the
        // expressive surface is gated behind these annotations even in 1.5.0-alpha.
        // Opting in once here beats sprinkling @OptIn through every screen.
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

// Signed with the same key as the rest of the module, because the daemon embeds this
// manager's certificate and verifies the APK it serves against it. Same four Gradle
// properties the rest of the build uses, falling back to the debug key when there is no
// keystore -- which is every build that is not CI's.
val keystore = providers.gradleProperty("androidStoreFile").map { rootProject.file(it) }.orNull
val signed = keystore?.exists() == true

val defaultManagerPackageName = rootProject.extra["defaultManagerPackageName"] as String
val injectedPackageName = rootProject.extra["injectedPackageName"] as String

val versionHash = providers.environmentVariable("GITHUB_SHA").orNull?.take(8) ?: "local"

android {
    namespace = "org.matrix.vector.manager"

    buildFeatures {
        compose = true
        buildConfig = true
        // The upstream daemon interface is an AIDL one, and it is vendored here rather than
        // rewritten as a hand-written interface because the debug demo subclasses its Stub() --
        // a fake that stops compiling when the daemon grows a question is the point of it.
        aidl = true
    }

    defaultConfig {
        applicationId = defaultManagerPackageName
        buildConfigField("String", "VERSION_HASH", "\"$versionHash\"")
        buildConfigField("String", "MANAGER_PACKAGE_NAME", "\"$defaultManagerPackageName\"")
        buildConfigField("String", "INJECTED_PACKAGE_NAME", "\"$injectedPackageName\"")

        // The languages this module is actually translated into, listed from the resource
        // folders that carry our own strings.xml. AssetManager.getLocales() cannot answer
        // this: it reports every locale any dependency ships a resource for.
        //
        // English is added by hand because it is not in a `values-xx` folder to be found:
        // it lives in `values/`, the base the others fall back to.
        val translations =
            (listOf("en") +
                    file("src/main/res")
                        .listFiles()
                        .orEmpty()
                        .filter { it.isDirectory && it.name.startsWith("values-") }
                        .filter { File(it, "strings.xml").exists() }
                        .map { it.name.removePrefix("values-").replace("-r", "-") })
                .sorted()
        buildConfigField("String", "TRANSLATIONS", "\"${translations.joinToString(",")}\"")
    }

    packaging {
        resources {
            excludes += "META-INF/**"
            excludes += "okhttp3/**"
            excludes += "kotlin/**"
            excludes += "**.properties"
            excludes += "**.bin"
        }
    }

    dependenciesInfo.includeInApk = false

    if (signed) {
        signingConfigs.create("apksign") {
            storeFile = keystore
            storePassword = providers.gradleProperty("androidStorePassword").orNull
            keyAlias = providers.gradleProperty("androidKeyAlias").orNull
            keyPassword = providers.gradleProperty("androidKeyPassword").orNull
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
        }
        configureEach {
            signingConfig = signingConfigs.getByName(if (signed) "apksign" else "debug")
            if (!signed) logger.info("manager: no keystore, signing with debug")
        }
    }
}

dependencies {
    implementation(projects.services.managerService)
    implementation(projects.managerUi)

    // ParcelableListSlice, which the vendored IManagerService AIDL imports.
    implementation(libs.rikkax.parcelablelist)

    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // The Compose BOM aligns every androidx.compose.* artifact; none of them is pinned
    // individually in the version catalog.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Tooling dependencies, debug builds only, for UI previews.
    debugImplementation(libs.androidx.compose.ui.tooling)
}
