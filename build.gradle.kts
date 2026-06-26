plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

javafx {
    version = "21.0.4"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.swing")
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    // Decode Modrinth's WebP mod icons (JavaFX can't read WebP natively).
    implementation("org.sejda.imageio:webp-imageio:0.1.6")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    // Launcher does NOT extend Application — avoids "JavaFX runtime components missing" on classpath
    mainClass = "com.kano.launcher.Launcher"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    // run from the project dir so relative paths (instances/, cache/) resolve predictably
    workingDir = rootDir
}

// ---- packaging: self-contained app for sharing with friends ----
// `gradlew packageApp` → build/dist/KanoLauncher/ (KanoLauncher.exe + bundled Java runtime, ~90 MB).
// `gradlew zipApp`     → build/dist/KanoLauncher-<ver>-win.zip — the single file to send friends.
// jpackage app-image needs no extra tooling (no WiX); friends just unzip and run the .exe, no Java install.
val appVersion = "1.3.0"
val runtimeModules = listOf(
    "java.base", "java.desktop", "java.logging", "java.management", "java.naming",
    "java.net.http", "java.prefs", "java.scripting", "java.sql", "java.xml",
    "jdk.crypto.cryptoki", "jdk.crypto.ec", "jdk.unsupported", "jdk.zipfs"
).joinToString(",")

tasks.register<Exec>("packageApp") {
    group = "distribution"
    description = "Build a self-contained app-image (KanoLauncher.exe + bundled runtime) via jpackage"
    dependsOn("installDist")
    val toolchain = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    doFirst {
        delete(layout.buildDirectory.dir("dist"))
        val javaHome = toolchain.get().metadata.installationPath.asFile
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val jpackage = File(javaHome, "bin/jpackage" + if (isWin) ".exe" else "")
        val libDir = layout.buildDirectory.dir("install/KanoLauncher/lib").get().asFile
        val dest = layout.buildDirectory.dir("dist").get().asFile
        val args = mutableListOf(
            jpackage.absolutePath,
            "--type", "app-image",
            "--name", "KanoLauncher",
            "--input", libDir.absolutePath,
            "--main-jar", "KanoLauncher.jar",
            "--main-class", "com.kano.launcher.Launcher",
            "--add-modules", runtimeModules,
            "--java-options", "-Xmx512m",
            "--app-version", appVersion,
            "--vendor", "ChaosCraft",
            "--dest", dest.absolutePath
        )
        val ico = file("src/main/resources/com/kano/launcher/king.ico")
        if (ico.exists()) { args.add("--icon"); args.add(ico.absolutePath) }
        commandLine(args)
    }
}

tasks.register<Zip>("zipApp") {
    group = "distribution"
    description = "Zip the packaged app into a single file to send to friends"
    dependsOn("packageApp")
    from(layout.buildDirectory.dir("dist/KanoLauncher")) { into("KanoLauncher") }
    archiveFileName.set("KanoLauncher-$appVersion-win.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
}
