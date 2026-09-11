plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.dashnex"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
}

fun dotEnv(): Map<String, String> {
    val f = rootProject.file(".env")
    if (!f.exists()) return emptyMap()
    return f.readLines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { it.substringBefore("=").trim().removePrefix("export ").trim() to it.substringAfter("=").trim() }
}

tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs tests against a real Cloudflare D1 database (D1_ACCOUNT_ID, D1_TOKEN, D1_DATABASE)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    environment(dotEnv())
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.test)
}

tasks.shadowJar {
    archiveClassifier.set("all")
    relocate("com.fasterxml.jackson", "com.dashnex.d1.jdbc.shaded.jackson")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
