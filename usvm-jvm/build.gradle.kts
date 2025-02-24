@file:Suppress("PropertyName", "HasPlatformType")

import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists


plugins {
    id("usvm.kotlin-conventions")
    id("org.springframework.boot") version "3.2.0"
    id("io.spring.dependency-management") version "1.1.4"
}

val samples by sourceSets.creating {
    java {
        srcDir("src/samples/java")
    }
}

val `samples-jdk11` by sourceSets.creating {
    java {
        srcDir("src/samples-jdk11/java")
    }
}

val `sample-approximations` by sourceSets.creating {
    java {
        srcDir("src/sample-approximations/java")
    }
}

repositories {
    mavenLocal()
}

val approximations by configurations.creating
val approximationsRepo = "org.usvm.approximations.java.stdlib"
val approximationsVersion = "0.0.0"

dependencies {
    implementation(project(":usvm-core"))
    implementation(project(":usvm-jvm-dataflow"))

    implementation(Libs.jacodb_api_jvm)
    implementation(Libs.jacodb_core)
    implementation(Libs.jacodb_approximations)

    implementation(project("usvm-jvm-api"))
    implementation(project("usvm-jvm-test-api"))
    implementation(project(":usvm-jvm:usvm-jvm-util"))
    implementation(project(":usvm-jvm:usvm-jvm-reproducer"))

    implementation(Libs.ksmt_runner)
    implementation(Libs.ksmt_yices)
    implementation(Libs.ksmt_cvc5)
    implementation(Libs.ksmt_symfpu)

    testImplementation(Libs.mockk)
    testImplementation(Libs.junit_jupiter_params)
    testImplementation(Libs.logback)

    testImplementation(samples.output)

    // https://mvnrepository.com/artifact/org.burningwave/core
    // Use it to export all modules to all
    testImplementation("org.burningwave:core:12.62.7")

    approximations(approximationsRepo, "approximations", approximationsVersion)
    testImplementation(approximationsRepo, "tests", approximationsVersion)
}

val springApproximationsDeps by configurations.creating

dependencies {
    springApproximationsDeps("org.springframework.boot:spring-boot-starter-test:3.2.0")
    springApproximationsDeps("org.springframework.boot:spring-boot-starter-web:3.2.0")
    springApproximationsDeps("org.springframework.boot:spring-boot-starter-security:3.3.4")
    springApproximationsDeps("org.springframework.security:spring-security-test:6.2.0")
    springApproximationsDeps("org.springframework:spring-jcl:6.1.1")
    springApproximationsDeps("org.springframework.boot:spring-boot-starter-data-jpa:3.2.0")
}

val samplesImplementation: Configuration by configurations.getting

dependencies {
    samplesImplementation("org.projectlombok:lombok:${Versions.Samples.lombok}")
    samplesImplementation("org.slf4j:slf4j-api:${Versions.Samples.slf4j}")
    samplesImplementation("javax.validation:validation-api:${Versions.Samples.javaxValidation}")
    samplesImplementation("com.github.stephenc.findbugs:findbugs-annotations:${Versions.Samples.findBugs}")
    samplesImplementation("org.jetbrains:annotations:${Versions.Samples.jetbrainsAnnotations}")

    // Use usvm-api in samples for makeSymbolic, assume, etc.
    samplesImplementation(project("usvm-jvm-api"))

    testImplementation(project(":usvm-jvm-instrumentation"))
}

val `sample-approximationsCompileOnly`: Configuration by configurations.getting

dependencies {
    `sample-approximationsCompileOnly`(samples.output)
    `sample-approximationsCompileOnly`(project("usvm-jvm-api"))
    `sample-approximationsCompileOnly`(Libs.jacodb_api_jvm)
    `sample-approximationsCompileOnly`(Libs.jacodb_approximations)
}

val testSamples by configurations.creating
val testSamplesWithApproximations by configurations.creating

val compileSamplesJdk11 = tasks.register<JavaCompile>("compileSamplesJdk11") {
    sourceCompatibility = JavaVersion.VERSION_11.toString()
    targetCompatibility = JavaVersion.VERSION_11.toString()

    source = `samples-jdk11`.java
    classpath = `samples-jdk11`.compileClasspath
    options.sourcepath = `samples-jdk11`.java
    destinationDirectory = `samples-jdk11`.java.destinationDirectory
}

dependencies {
    testSamples(samples.output)
    testSamples(project("usvm-jvm-api"))
    testSamples(files(`samples-jdk11`.java.destinationDirectory))

    testSamplesWithApproximations(samples.output)
    testSamplesWithApproximations(project("usvm-jvm-api"))
    testSamplesWithApproximations(`sample-approximations`.output)
    testSamplesWithApproximations(approximationsRepo, "tests", approximationsVersion)
}

val usvmApiJarConfiguration by configurations.creating
dependencies {
    usvmApiJarConfiguration(project("usvm-jvm-api"))
}

tasks.withType<Test> {
    dependsOn(compileSamplesJdk11, testSamples, testSamplesWithApproximations)

    val usvmApiJarPath = usvmApiJarConfiguration.resolvedConfiguration.files.single()
    val usvmApproximationJarPath = approximations.resolvedConfiguration.files.single()

    environment("usvm.jvm.api.jar.path", usvmApiJarPath.absolutePath)
    environment("usvm.jvm.approximations.jar.path", usvmApproximationJarPath.absolutePath)

    environment("usvm.jvm.test.samples", testSamples.asPath)
    environment("usvm.jvm.test.samples.approximations", testSamplesWithApproximations.asPath)
}


tasks {
    register<Jar>("testJar") {
        group = "jar"
        shouldRunAfter("compileTestKotlin")
        archiveClassifier.set("test")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        val contents = sourceSets.getByName("samples").output

        from(contents)
        dependsOn(getByName("compileSamplesJava"), configurations.testCompileClasspath)
        dependsOn(configurations.compileClasspath)
    }
}

tasks.getByName("compileTestKotlin").finalizedBy("testJar")

tasks.withType<Test> {
    environment(
        "usvm-test-jar",
        layout
            .buildDirectory
            .file("libs/usvm-jvm-test.jar")
            .get().asFile.absolutePath
    )
    environment(
        "usvm-jvm-instrumentation-jar",
        project(":usvm-jvm-instrumentation")
            .layout
            .buildDirectory
            .file("libs/usvm-jvm-instrumentation-runner.jar")
            .get().asFile.absolutePath
    )
    environment(
        "usvm-jvm-collectors-jar",
        project(":usvm-jvm-instrumentation")
            .layout
            .buildDirectory
            .file("libs/usvm-jvm-instrumentation-collectors.jar")
            .get().asFile.absolutePath
    )
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web:3.3.4")
    implementation("org.springframework.boot:spring-boot-starter-test:3.3.4")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa:3.3.4")
    implementation("org.apache.xmlbeans:xmlbeans:5.2.1")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf:3.3.4")
}

tasks.register<JavaExec>("runWebBench") {
    mainClass.set("bench.WebBenchKt")
    classpath = sourceSets.test.get().runtimeClasspath

    systemProperty("jdk.util.jar.enableMultiRelease", false)

    val usvmApiJarPath = usvmApiJarConfiguration.resolvedConfiguration.files.single()
    val usvmApproximationJarPath = approximations.resolvedConfiguration.files.single()
    val springApproximationDepsJarPath = springApproximationsDeps.resolvedConfiguration.files
    val absolutePaths = springApproximationDepsJarPath.joinToString(";") { it.absolutePath }

    // TODO: norm? #CM #Valya
    systemProperty("usvm.jvm.springApproximationsDeps.paths", absolutePaths)
    val currentDir = Path(System.getProperty("user.dir"))
    val generatedDir = currentDir.resolve("generated")
    generatedDir.createDirectories()
    systemProperty("generatedDir", generatedDir.absolutePathString())
    val lambdaDir = generatedDir.resolve("lambdas")
    if (lambdaDir.exists()) {
        lambdaDir.toFile().listFiles()?.forEach { it.deleteRecursively() }
    } else {
        lambdaDir.createDirectories()
    }
    systemProperty("lambdasDir", lambdaDir.absolutePathString())

    environment("usvm.jvm.api.jar.path", usvmApiJarPath.absolutePath)
    environment("usvm.jvm.approximations.jar.path", usvmApproximationJarPath.absolutePath)

    jvmArgs = listOf("-Xmx10g") + mutableListOf<String>().apply {
        add("-Djava.security.manager -Djava.security.policy=webExplorationPolicy.policy")
        add("-Djdk.internal.lambda.dumpProxyClasses=${lambdaDir.absolutePathString()}")
        openPackage("java.base", "jdk.internal.misc")
        openPackage("java.base", "java.lang")
        openPackage("java.base", "java.lang.reflect")
        openPackage("java.base", "sun.security.provider")
        openPackage("java.base", "jdk.internal.event")
        openPackage("java.base", "jdk.internal.jimage")
        openPackage("java.base", "jdk.internal.jimage.decompressor")
        openPackage("java.base", "jdk.internal.jmod")
        openPackage("java.base", "jdk.internal.jtrfs")
        openPackage("java.base", "jdk.internal.loader")
        openPackage("java.base", "jdk.internal.logger")
        openPackage("java.base", "jdk.internal.math")
        openPackage("java.base", "jdk.internal.misc")
        openPackage("java.base", "jdk.internal.module")
        openPackage("java.base", "jdk.internal.org.objectweb.asm.commons")
        openPackage("java.base", "jdk.internal.org.objectweb.asm.signature")
        openPackage("java.base", "jdk.internal.org.objectweb.asm.tree")
        openPackage("java.base", "jdk.internal.org.objectweb.asm.tree.analysis")
        openPackage("java.base", "jdk.internal.org.objectweb.asm.util")
        openPackage("java.base", "jdk.internal.org.xml.sax")
        openPackage("java.base", "jdk.internal.org.xml.sax.helpers")
        openPackage("java.base", "jdk.internal.perf")
        openPackage("java.base", "jdk.internal.platform")
        openPackage("java.base", "jdk.internal.ref")
        openPackage("java.base", "jdk.internal.reflect")
        openPackage("java.base", "jdk.internal.util")
        openPackage("java.base", "jdk.internal.util.jar")
        openPackage("java.base", "jdk.internal.util.xml")
        openPackage("java.base", "jdk.internal.util.xml.impl")
        openPackage("java.base", "jdk.internal.vm")
        openPackage("java.base", "jdk.internal.vm.annotation")
        openPackage("java.base", "java.util.concurrent.atomic")
        openPackage("java.base", "java.io")
        openPackage("java.base", "java.util.zip")
        openPackage("java.base", "java.util.concurrent")
        openPackage("java.base", "sun.security.util")
        openPackage("java.base", "java.lang.invoke")
        openPackage("java.base", "java.lang.ref")
        openPackage("java.base", "java.lang.constant")
        openPackage("java.base", "java.util")
        openPackage("java.base", "java.util.concurrent.locks")
        openPackage("java.management", "javax.management")
        openPackage("java.base", "java.nio.charset")
        openPackage("java.base", "java.util.regex")
        openPackage("java.base", "java.net")
        openPackage("java.base", "sun.util.locale")
        openPackage("java.base", "java.util.stream")
        openPackage("java.base", "java.security")
        openPackage("java.base", "java.time")
        openPackage("java.base", "jdk.internal.access")
        openPackage("java.base", "sun.reflect.annotation")
        openPackage("java.base", "sun.reflect.generics.reflectiveObjects")
        openPackage("java.base", "sun.reflect.generics.factory")
        openPackage("java.base", "sun.reflect.generics.tree")
        openPackage("java.base", "sun.reflect.generics.scope")
        openPackage("java.base", "sun.invoke.util")
        openPackage("java.base", "sun.nio.cs")
        openPackage("java.base", "sun.nio.fs")
        openPackage("java.base", "java.nio")
        openPackage("java.logging", "java.util.logging")
        openPackage("java.base", "java.time.format")
        openPackage("java.base", "java.time.zone")
        openPackage("java.base", "java.time.temporal")
        openPackage("java.base", "java.text")
        openPackage("java.base", "sun.util.calendar")
        openPackage("java.base", "sun.net.www.protocol.jar")
        openPackage("java.base", "java.util.jar")
        openPackage("java.base", "java.nio.file.attribute")
        openPackage("java.base", "java.util.function")
        openPackage("java.desktop", "java.beans")
        openPackage("java.xml", "com.sun.org.apache.xerces.internal.impl.xs")
        openPackage("java.base", "java.math")
        openPackage("java.base", "java.nio.file")
        openPackage("java.base", "java.nio.channels")
        openPackage("java.base", "javax.net.ssl")
        openPackage("java.base", "java.lang.annotation")
        openPackage("java.base", "java.lang.runtime")
        openPackage("java.base", "javax.crypto")
        openPackage("jdk.zipfs", "jdk.nio.zipfs")
        openPackage("java.base", "java.nio.file.spi")
        openPackage("java.base", "jdk.internal.jrtfs")
        openPackage("java.instrument", "sun.instrument")
        openPackage("java.xml", "com.sun.xml.internal.stream")
        openPackage("java.xml", "com.sun.org.apache.xerces.internal.impl")
        openPackage("java.xml", "com.sun.org.apache.xerces.internal.utils")
        openPackage("java.sql", "java.sql")
        openPackage("java.base", "sun.nio.ch")
        openPackage("java.base", "sun.net.util")
        openPackage("java.xml", "com.sun.org.apache.xalan.internal.xsltc.trax")
        exportPackage("java.base", "jdk.internal.access.foreign")
        exportPackage("java.base", "sun.security.action")
        exportPackage("java.base", "sun.util.locale")
        exportPackage("java.base", "jdk.internal.misc")
        exportPackage("java.base", "jdk.internal.reflect")
        exportPackage("java.base", "sun.nio.cs")
        exportPackage("java.xml", "com.sun.org.apache.xerces.internal.impl.xs.util")
        exportPackage("java.base", "jdk.internal.loader")
        add("--illegal-access=warn")
        add("-XX:+UseParallelGC")
        addModule("jdk.incubator.foreign")
    }
}

fun MutableList<String>.openPackage(module: String, pakage: String) {
    add("--add-opens")
    add("$module/$pakage=ALL-UNNAMED")
}

fun MutableList<String>.exportPackage(module: String, pakage: String) {
    add("--add-exports")
    add("$module/$pakage=ALL-UNNAMED")
}

fun MutableList<String>.addModule(module: String) {
    add("--add-modules")
    add(module)
}

fun JavaExec.addEnvIfExists(envName: String, path: String) {
    val file = File(path)
    if (!file.exists()) {
        println("Not found $envName at $path")
        return
    }

    environment(envName, file.absolutePath)
}
