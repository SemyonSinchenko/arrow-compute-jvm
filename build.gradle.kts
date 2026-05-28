import org.gradle.api.GradleException
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.java
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType

plugins {
    java
    id("me.champeau.jmh") version "0.7.3"
}

group = "io.github.semyonsinchenko"
version = "0.1.0-SNAPSHOT"

val arrowVersion = "16.1.0"
val sharedJvmArgs = listOf(
    "--add-modules",
    "jdk.incubator.vector",
    "--enable-native-access=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED"
)

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.arrow:arrow-vector:$arrowVersion")
    implementation("org.apache.arrow:arrow-memory-core:$arrowVersion")
    implementation("org.apache.arrow:arrow-memory-unsafe:$arrowVersion")
    implementation("org.apache.arrow:arrow-algorithm:$arrowVersion")

    compileOnly("org.codehaus.janino:janino:3.1.12")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.codehaus.janino:janino:3.1.12")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")

    jmhRuntimeOnly("org.codehaus.janino:janino:3.1.12")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs(sharedJvmArgs)
    systemProperty("arrow.memory.debug.allocator", "true")
}

tasks.withType<JavaExec>().configureEach {
    if (name == "jmh") {
        jvmArgs(sharedJvmArgs)
    }
}

tasks.named("check") {
    doFirst {
        val runtimeFeature = Runtime.version().feature()
        if (runtimeFeature < 25) {
            throw GradleException("Rule[toolchain-runtime]: Java 25+ runtime required, found Java $runtimeFeature")
        }

        val forbidden = setOf("spring", "guice", "slf4j", "log4j", "jackson-databind", "cglib")
        val dependencyScopes = setOf("implementation", "api", "compileOnly", "runtimeOnly")
        val violations = configurations
            .filter { it.name in dependencyScopes }
            .flatMap { cfg ->
                cfg.dependencies.map { dep -> "${dep.group}:${dep.name}" }
            }
            .filter { dep -> forbidden.any { marker -> dep.contains(marker, ignoreCase = true) } }
            .distinct()

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Rule[forbidden-dependencies]: Found forbidden dependencies: ${violations.joinToString(", ")}"
            )
        }

        val testTask = tasks.getByName("test") as Test
        val expectedShared = listOf(
            "--add-modules",
            "jdk.incubator.vector",
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED"
        )
        val testJvmArgs = testTask.jvmArgs ?: emptyList()
        if (!testJvmArgs.containsAll(expectedShared)) {
            throw GradleException("Rule[jvm-flags]: test task missing shared JVM flags")
        }
    }
}

tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val jmhInclude = providers.gradleProperty("jmhInclude")
val jmhExclude = providers.gradleProperty("jmhExclude")
val jmhParamPrefix = "jmhParam."
val jmhBenchmarkParams = gradle.startParameter.projectProperties
    .filterKeys { it.startsWith(jmhParamPrefix) }
    .mapKeys { (key, _) -> key.removePrefix(jmhParamPrefix) }
    .filterKeys { it.isNotBlank() }
    .mapValues { (_, value) ->
        value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    .filterValues { it.isNotEmpty() }

jmh {
    includes.set(
        jmhInclude
            .map { listOf(it) }
            .orElse(listOf(".*"))
    )
    excludes.set(
        jmhExclude
            .map { listOf(it) }
            .orElse(emptyList())
    )
    jmhBenchmarkParams.forEach { (name, values) ->
        benchmarkParameters.put(name, objects.listProperty<String>().value(values))
    }
    warmupIterations.set(3)
    iterations.set(3)
    fork.set(2)
    jvmArgsAppend.set(sharedJvmArgs)
}
