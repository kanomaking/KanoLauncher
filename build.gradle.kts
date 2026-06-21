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
}

application {
    // Launcher does NOT extend Application — avoids "JavaFX runtime components missing" on classpath
    mainClass = "com.kano.launcher.Launcher"
}

tasks.named<JavaExec>("run") {
    // run from the project dir so relative paths (instances/, cache/) resolve predictably
    workingDir = rootDir
}
