// Manga Epic: minimal build for the vendored parsers fork.
// The upstream build script (bundle signing/dex tasks in buildSrc) is not used:
// the app takes the parsers as a plain composite-build project dependency and
// runtime bundle downloads are disabled app-side (bundle key "none").
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

group = "org.koitharu"
version = "1.0"

val sourcesMapFile = layout.buildDirectory.file("parsersTests/sources-map.tsv")

ksp {
    arg("summaryOutputDir", "${projectDir}/.github")
    arg("projectDir", projectDir.absolutePath)
    arg("sourcesMapFile", sourcesMapFile.get().asFile.absolutePath)
}

val generateParsersBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/parsersBuildInfo/kotlin")
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("org/koitharu/kotatsu/parsers/core/ParsersBuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package org.koitharu.kotatsu.parsers.core

            internal object ParsersBuildInfo {

            	const val VERSION: Long = 1794530216L

            	const val REVISION: String = "3432a85ac0"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDirs("build/generated/ksp/main/kotlin")
    sourceSets["main"].kotlin.srcDir(generateParsersBuildInfo)
}

sourceSets {
    main {
        kotlin.srcDirs("src/main/kotlin", "src/contract/kotlin")
        java.srcDirs("src/main/java")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=org.koitharu.kotatsu.parsers.InternalParsersApi",
            "-opt-in=org.koitharu.kotatsu.parsers.DirectParsersAccess",
        )
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.json)
    implementation(libs.androidx.collection)
    api(libs.jsoup)

    ksp(project(":kotatsu-parsers-ksp"))
}
