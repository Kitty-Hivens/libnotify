@file:OptIn(ExperimentalAbiValidation::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import java.util.UUID

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    // vanniktech: applies maven-publish + signing, targets the new Central
    // Portal. Replaces the bare `maven-publish` + hand-rolled publishing block.
    alias(libs.plugins.mavenPublish)
    // Declared so the `signing { }` type-safe accessor + useGpgCmd() resolve
    // (vanniktech applies signing transitively, but that doesn't generate the
    // Kotlin DSL accessor for this script). Idempotent.
    `signing`
}

// SmokeMain lives in the TEST source set (src/test) so it stays OUT of the
// published library jar yet keeps `internal` access to the backends -- it
// reaches ObjcBindings, which only the test compilation is a friend of. The
// test runtime classpath already carries the slf4j-simple binding
// (testRuntimeOnly), so runSmoke reuses it rather than a dedicated
// configuration. Same shape as libtray.
tasks.register<JavaExec>("runSmoke") {
    group = "verification"
    description = "Post a few system notifications and print activation events. Ctrl-C to exit."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.hivens.libnotify.SmokeMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // macOS pins NSUserNotificationCenter delivery to the Cocoa main run
    // loop on OS thread 0. Without `-XstartOnFirstThread` the JVM main
    // thread is a worker thread AppKit never pumps, so notifications are
    // enqueued but never delivered and the activation delegate never
    // fires. The flag makes JVM main == OS thread 0 == Cocoa main thread.
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        jvmArgs("-XstartOnFirstThread")
    }
    // Show INFO-level logs so the smoke harness surfaces backend
    // diagnostics (D-Bus server name, NIM/Notify ids, GetLastError on
    // failure paths). Default slf4j-simple level is INFO already, but the
    // system property makes the intent explicit and future-proofs against
    // the default flipping in a newer release.
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")
    systemProperty("org.slf4j.simpleLogger.showDateTime", "true")
    systemProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS")
    standardInput = System.`in`
    // Don't fail the build if a developer aborts the smoke with Ctrl-C --
    // SIGINT exits the JVM with code 130, JavaExec treats that as failure.
    isIgnoreExitValue = true
}

group = "dev.hivens"
// Version comes from the git tag at CI time via `-PappVersion=<tag>`;
// falls back to `git describe` for local development.
version = providers.gradleProperty("appVersion")
    .orElse(providers.exec {
        commandLine("git", "describe", "--tags", "--always", "--dirty")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().ifEmpty { "0.0.0-SNAPSHOT" } })
    .getOrElse("0.0.0-SNAPSHOT")

java {
    // Source / target Java 22 -- Project Panama (java.lang.foreign)
    // finalized as JEP 454. Compiles with any JDK >= 22 in the build
    // environment; CI uses Temurin 22, the maintainer's local install
    // is Liberica 25 (compatible). No toolchain auto-provision plugin --
    // contributors install their own JDK rather than have Gradle
    // download one transparently.
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
    // No withSourcesJar()/withJavadocJar() here -- the vanniktech plugin
    // builds and publishes the sources + javadoc jars itself; declaring
    // them again would double-register the artifacts.
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_22)
        // Explicit API mode: this is a library, every public symbol must
        // declare its visibility and carry an explicit return type. Keeps
        // the published surface deliberate rather than accidental.
        // (Configured via the `kotlin { explicitApi() }` call below.)
        freeCompilerArgs.addAll(
            "-jvm-default=enable",  // Generate Java 8+ default methods for interface APIs.
            // Not -Xjdk-release=22, though it would pin Kotlin to the JDK 22 class
            // library. With it the compiler loses the polymorphic signature of
            // MethodHandle.invokeExact and emits a plain varargs call, so every
            // native call in this library fails at run time with
            // WrongMethodTypeException. CI compiles on JDK 22, which is where the
            // floor the README promises is enforced.
        )
        // Every warning is an error. Warnings pile up unseen behind the
        // build cache (a cached compile replays without re-emitting them);
        // as errors they fail the compile, so none caches green.
        allWarningsAsErrors.set(true)
    }
    explicitApi()
    // The public API as a committed text dump (api/). check fails when the
    // compiled API drifts from it, so every API change shows up in review as a
    // diff of that file. Accept a deliberate change with updateKotlinAbi.
    abiValidation {
        enabled.set(true)
    }
}

tasks.named("check") {
    dependsOn("checkKotlinAbi")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(22)
}

// How many tests ran, printed on every run, with a ceiling on how many may skip
// and the classes that must run. Without it a suite that skipped itself into
// silence looks exactly like one that passed: Gradle prints totals only when
// something fails. The integration suite skips on purpose where its platform is
// not the host's, so its limits depend on where it runs:
//   - Linux runs everything but PlatformSmokeIT, hence one allowed skip.
//   - Windows and macOS skip the bus suite and run PlatformSmokeIT, on CI only.
//     A count cannot guard that, the bus-free knownGap tests always run there,
//     so the smoke test is required by name.
// Raise a limit deliberately with -PmaxSkippedTests when a gap is real.
val hostIsLinux = System.getProperty("os.name").lowercase().contains("linux")
val onCi = providers.environmentVariable("CI").orNull == "true"
tasks.withType<Test>().configureEach {
    val integration = name == "integrationTest"
    val maxSkipped = providers.gradleProperty("maxSkippedTests").map { it.toInt() }.getOrElse(
        when {
            // DBusLayoutTest's two tests need libdbus, so they skip elsewhere.
            !integration -> if (hostIsLinux) 0 else 2
            hostIsLinux -> 1
            else -> Int.MAX_VALUE
        },
    )
    val mustRun = if (integration && !hostIsLinux && onCi) listOf("PlatformSmokeIT") else emptyList()
    // Inputs, because they decide whether the task passes and Gradle keys its
    // cache on inputs alone: a lowered ceiling must not be served an old green.
    inputs.property("libnotifyMaxSkipped", maxSkipped)
    inputs.property("libnotifyMustRun", mustRun)
    val xmlDir = reports.junitXml.outputLocation
    val label = path
    doLast {
        var total = 0
        var skipped = 0
        val names = mutableListOf<String>()
        val ranClasses = mutableSetOf<String>()
        val head = Regex("<testsuite\\b[^>]*")
        val case = Regex("<testcase name=\"([^\"]*)\" classname=\"([^\"]*)\"[^>]*>(\\s*<skipped)?")
        fun count(text: String, attribute: String): Int =
            Regex("\\b$attribute=\"(\\d+)\"").find(text)?.groupValues?.get(1)?.toInt() ?: 0
        for (f in xmlDir.get().asFile.listFiles().orEmpty()) {
            if (!f.name.endsWith(".xml")) continue
            val text = f.readText()
            val suite = head.find(text) ?: continue
            total += count(suite.value, "tests")
            skipped += count(suite.value, "skipped")
            for (m in case.findAll(text)) {
                val className = m.groupValues[2].substringAfterLast('.')
                if (m.groupValues[3].isNotEmpty()) {
                    names += className + " > " + m.groupValues[1]
                } else {
                    ranClasses += className
                }
            }
        }
        val ran = total - skipped
        logger.lifecycle("$label: $ran of $total tests ran, $skipped skipped")
        val missing = mustRun.filterNot { it in ranClasses }
        if (skipped > maxSkipped || missing.isNotEmpty()) {
            throw GradleException(
                "$label ran $ran and skipped $skipped tests: at most $maxSkipped may skip, and " +
                    "${missing.ifEmpty { listOf("nothing") }.joinToString()} had to run but did not. " +
                    "Something the suite needs is missing rather than the suite passing.\n" +
                    names.sorted().joinToString("\n") { "  $it" },
            )
        }
    }
}

dependencies {
    // SLF4J only -- consumers wire their own backend. The library logs at
    // DEBUG/INFO/WARN; nothing should fire at ERROR in normal operation
    // (failures degrade by returning null/false, not by throwing).
    api(libs.slf4j.api)

    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
    testImplementation(libs.kotest.assertions)
}

tasks.test {
    useJUnitPlatform()
    // Native code under test will eventually want this; harmless on tests
    // that don't reach Panama.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Black-box tests against the public API, on a private dbus-daemon with a fake
// notification server. The address goes into the worker's environment before it
// starts, so libdbus can never reach the desktop's real session bus from here.
// One worker per class: libdbus caches the bus address per process, and a class
// may kill its bus.
testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(libs.kotest.assertions)
                runtimeOnly(libs.slf4j.simple)
            }
            targets.configureEach {
                testTask.configure {
                    jvmArgs("--enable-native-access=ALL-UNNAMED")
                    forkEvery = 1
                    maxParallelForks = 1
                    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")
                    // The suite tests the system's libdbus and dbus-daemon, which no
                    // task input captures, so a cached or up-to-date result proves
                    // nothing about this machine or this tag.
                    outputs.cacheIf { false }
                    outputs.upToDateWhen { false }
                    doFirst {
                        val bus = "unix:abstract=libnotify-it-${UUID.randomUUID()}"
                        (this as Test).environment("DBUS_SESSION_BUS_ADDRESS", bus)
                    }
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to "libnotify",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Kitty-Hivens",
        )
    }
}

// Maven Central publishing via the vanniktech plugin (new Central Portal,
// central.sonatype.com). It builds the sources + javadoc jars, signs every
// artifact, assembles the deployment bundle, and uploads it to the Central
// Portal Publisher API.
//
// Secrets come from ~/.gradle/gradle.properties or env -- NEVER commit them:
//   mavenCentralUsername / mavenCentralPassword : Central Portal user token
//       (Central Portal -> Account -> Generate User Token), NOT the login.
//   signingInMemoryKey / signingInMemoryKeyPassword (+ optional ...KeyId) :
//       the ASCII-armored GPG private key + its passphrase.
//
// Publish: push a vX.Y.Z tag and .github/workflows/publish.yml does the rest,
// passing the tag as -PappVersion and signing with the key CI holds. From a
// workstation the same release is `./gradlew publishToMavenCentral
// -PappVersion=X.Y.Z --no-configuration-cache`, signing through the keyring.
// Pass the version either way: gradle.properties pins appVersion to a
// SNAPSHOT, so the git-describe fallback above never gets to run.
// automaticRelease then carries the deployment all the way through instead of
// leaving it VALIDATED in the portal waiting for a manual Publish click.
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    // -PskipSigning is for publishing to mavenLocal or from JitPack, where no
    // key is unlocked and signing, which runs for every repository, would stop
    // the publish outright. Central refuses an unsigned upload, so the switch
    // cannot ship anything unsigned.
    if (!providers.gradleProperty("skipSigning").isPresent) signAllPublications()
    coordinates("dev.hivens", "libnotify", project.version.toString())
    pom {
        name.set("libnotify")
        description.set("Cross-platform desktop notifications for JVM 22+ via Project Panama.")
        url.set("https://github.com/Kitty-Hivens/libnotify")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("kitty-hivens")
                name.set("Kitty-Hivens")
            }
        }
        scm {
            url.set("https://github.com/Kitty-Hivens/libnotify")
            connection.set("scm:git:https://github.com/Kitty-Hivens/libnotify.git")
        }
    }
}

signing {
    // Local release signs through the gpg keyring: BouncyCastle's in-memory
    // reader chokes on modern GnuPG secret-key exports ("checksum mismatch"),
    // so we hand signing to the gpg command. CI can still inject an in-memory
    // key via signingInMemoryKey (vanniktech handles that), in which case we
    // leave signing to it and skip useGpgCmd.
    if (!providers.gradleProperty("signingInMemoryKey").isPresent &&
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") == null
    ) {
        useGpgCmd()
    }
}
