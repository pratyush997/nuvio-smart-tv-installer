plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":core")) }
application { mainClass.set("tv.nuvio.installer.cli.MainKt") }
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}
