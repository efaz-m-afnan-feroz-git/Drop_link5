import java.util.Properties
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    alias(libs.plugins.android.application)
    // Versions come from the root buildscript classpath (see ../build.gradle.kts).
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// ---------------------------------------------------------------------------------------------
// Release signing: keystore.properties (local, git-ignored) or environment variables (CI).
// ---------------------------------------------------------------------------------------------
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "DROPLINK_KEYSTORE_PATH")
val releaseStorePassword = signingValue("storePassword", "DROPLINK_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "DROPLINK_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "DROPLINK_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null && rootProject.file(releaseStoreFile).exists() &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "app.droplink"
    compileSdk = 37 // Android 17

    defaultConfig {
        applicationId = "app.droplink"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true // Android 8.x still verifies v1 when present; v2+ is used on newer
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // One universal APK: no native code, no ABI/density splits.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", // duplicated by bcprov/bcpkix/bcutil
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)

    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit)
}

// ---------------------------------------------------------------------------------------------
// GMS ban: fail the build if anything resolves from Google Play services, Firebase or ML Kit.
// Runs before every build of every variant (wired into pre<Variant>Build), and on its own via
// ./gradlew verifyNoGoogleServices
// ---------------------------------------------------------------------------------------------
val bannedGroupPrefixes = listOf("com.google.android.gms", "com.google.firebase", "com.google.mlkit")

abstract class VerifyNoGoogleServicesTask : DefaultTask() {
    @get:Input
    abstract val bannedPrefixes: ListProperty<String>

    @get:Input
    abstract val resolvedModules: ListProperty<String>

    @get:Input
    abstract val configurationName: Property<String>

    @TaskAction
    fun verify() {
        val banned = bannedPrefixes.get()
        val offenders = resolvedModules.get().filter { id ->
            val group = id.substringBefore(':')
            banned.any { prefix -> group == prefix || group.startsWith("$prefix.") }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Google Play services / Firebase / ML Kit dependencies are banned in DropLink.\n" +
                    "Found in ${configurationName.get()}:\n" + offenders.joinToString("\n") { "  - $it" } +
                    "\nRun ./gradlew :app:dependencies to see which library pulls them in.",
            )
        }
        logger.lifecycle("GMS ban check passed for ${configurationName.get()} (${resolvedModules.get().size} modules).")
    }
}

fun collectModules(root: ResolvedComponentResult): List<String> {
    val seen = LinkedHashSet<String>()
    val visited = HashSet<ResolvedComponentResult>()
    val queue = ArrayDeque<ResolvedComponentResult>().apply { add(root) }
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (!visited.add(component)) continue
        component.moduleVersion?.let { mv ->
            if (component != root) seen += "${mv.group}:${mv.name}:${mv.version}"
        }
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue.add(it.selected) }
    }
    return seen.toList()
}

val verifyAll = tasks.register("verifyNoGoogleServices") {
    group = "verification"
    description = "Fails if any dependency comes from Google Play services, Firebase or ML Kit."
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val checks = listOf(
            "Runtime" to variant.runtimeConfiguration,
            "Compile" to variant.compileConfiguration,
        ).map { (kind, configuration) ->
            tasks.register<VerifyNoGoogleServicesTask>("verify${cap}${kind}NoGoogleServices") {
                group = "verification"
                bannedPrefixes.set(bannedGroupPrefixes)
                configurationName.set(configuration.name)
                resolvedModules.set(
                    configuration.incoming.resolutionResult.rootComponent.map { collectModules(it) },
                )
            }
        }
        verifyAll.configure { dependsOn(checks) }
        tasks.matching { it.name == "pre${cap}Build" }.configureEach { dependsOn(checks) }
    }
}
