import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

// TDLib's JVM jar bundles native libraries for every OS (~380 MB). Each package only needs the
// one for the OS it's built on, so the jar is repacked with just that platform's folder.
val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase().let { if (it == "aarch64" || it == "arm64") "arm64" else "x64" }
val tdlPlatform = when {
    "windows" in hostOs -> "windows/$hostArch"
    "mac" in hostOs -> "macos/$hostArch"
    else -> "linux/$hostArch"
}
// FFmpeg (through JavaCV) plays video and music; only this OS's native build is bundled.
val javacppPlatform = when {
    "windows" in hostOs -> "windows-x86_64"
    "mac" in hostOs -> if (hostArch == "arm64") "macosx-arm64" else "macosx-x86_64"
    else -> if (hostArch == "arm64") "linux-arm64" else "linux-x86_64"
}
val javacvVersion = "1.5.14"
val ffmpegVersion = "8.1.2-$javacvVersion"
val tdlJvm: Configuration by configurations.creating { isTransitive = false }
val slimTdlJar = tasks.register<Jar>("slimTdlJar") {
    archiveFileName.set("tdl-coroutines-jvm-${libs.versions.tdlCoroutines.get()}-${tdlPlatform.replace('/', '-')}.jar")
    destinationDirectory.set(layout.buildDirectory.dir("tdl"))
    from({ zipTree(tdlJvm.singleFile) }) {
        exclude("linux/**", "macos/**", "windows/**")
    }
    from({ zipTree(tdlJvm.singleFile) }) {
        include("$tdlPlatform/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    // Material You color math: builds the color scheme from the desktop's accent color.
    implementation("com.materialkolor:material-color-utilities:5.0.1")
    tdlJvm("dev.g000sha256:tdl-coroutines-jvm:${libs.versions.tdlCoroutines.get()}")
    implementation(files(slimTdlJar))
    // Built-in players: FFmpeg for video and audio (FLAC, Opus, MP3, AAC, MKV, WebM, H.264/HEVC…)
    // via JavaCV's frame grabber, without JavaCV's computer-vision extras.
    implementation("org.bytedeco:javacv:$javacvVersion") { isTransitive = false }
    implementation("org.bytedeco:javacpp:$javacvVersion")
    implementation("org.bytedeco:javacpp:$javacvVersion:$javacppPlatform")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$javacppPlatform")
    // Built-in PDF viewer.
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    testImplementation(kotlin("test-junit"))
}

compose.desktop {
    application {
        mainClass = "io.github.chibisueru.telegramdrive.desktop.MainKt"
        // The bundled runtime comes from a downloaded Temurin JDK 21 (see settings.gradle.kts).
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
            vendor.set(JvmVendorSpec.ADOPTIUM)
        }.get().metadata.installationPath.asFile.absolutePath
        jvmArgs += listOf(
            "-Xmx1g",
            "-Dtgdrive.version=${providers.gradleProperty("app.versionName").get()}",
            "--enable-native-access=ALL-UNNAMED",
            // Lets the app set its X11/XWayland window class, so the dock shows the right icon.
            "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "TelegramDrive"
            packageVersion = providers.gradleProperty("app.versionName").get()
            description = "Cloud drive that stores your files in your own Telegram account"
            vendor = "Chibisueru"
            copyright = "© 2026 Chibisueru"
            modules("java.naming", "jdk.unsupported", "jdk.crypto.ec")

            linux {
                packageName = "telegram-drive"
                iconFile.set(project.file("icons/icon.png"))
                debMaintainer = "Chibisueru <noreply@github.com>"
                appCategory = "Network"
                menuGroup = "Network;FileTransfer"
                shortcut = true
                rpmLicenseType = "Unspecified"
            }
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "Telegram Drive"
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Must stay the same forever: lets new versions upgrade the installed one.
                upgradeUuid = "41b12222-c553-4e32-bb02-5c8d769372bd"
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

// Headless screenshots of the main screens (for checking the UI without a display).
tasks.register<JavaExec>("screenshots") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.chibisueru.telegramdrive.desktop.ScreenshotsKt")
    val home = layout.buildDirectory.dir("screenshot-home").get().asFile
    systemProperty("tgdrive.home", home.absolutePath)
    systemProperty("java.awt.headless", "true")
    args(layout.buildDirectory.dir("screenshots").get().asFile.absolutePath, providers.gradleProperty("shotMode").getOrElse(""))
}
