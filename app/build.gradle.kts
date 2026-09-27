import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    checkstyle
}

// The configuration lives in the project root (checkstyle.xml, suppressions.xml), where the
// CheckStyle-IDEA plugin in Android Studio uses it as well. For Gradle it is copied into a separate
// directory, because otherwise ${config_loc} (= configDirectory) would make the whole project an input.
val checkstyleConfigDir = layout.buildDirectory.dir("checkstyle-config")

val checkstyleConfig by tasks.registering(Sync::class) {
    from(rootProject.file("checkstyle.xml"), rootProject.file("suppressions.xml"))
    into(checkstyleConfigDir)
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configDirectory = checkstyleConfigDir
    configFile = checkstyleConfigDir.get().file("checkstyle.xml").asFile
    maxWarnings = 0
}

val checkstyleMain by tasks.registering(Checkstyle::class) {
    description = "Checks the Java sources using checkstyle.xml."
    group = "verification"
    dependsOn(checkstyleConfig)
    source("src/main/java")
    include("**/*.java")
    classpath = files()
}

tasks.named("check") {
    dependsOn(checkstyleMain)
}

// Version derived from the build date: name "YYYY.MM.DD" (debug builds: "YYYY.MM.DD-<git short hash>"),
// code YYYYMMDD.
val buildDate: LocalDate = LocalDate.now()
val dateVersionName: String = buildDate.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
val dateVersionCode: Int = buildDate.format(DateTimeFormatter.BASIC_ISO_DATE).toInt()
val gitShortHash: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifEmpty { "nogit" }

android {
    namespace = "net.movingbits.fplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.movingbits.fplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = dateVersionCode
        versionName = dateVersionName
    }

    buildTypes {
        debug {
            versionNameSuffix = "-$gitShortHash"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.documentfile)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    implementation(libs.recyclerview)
}
