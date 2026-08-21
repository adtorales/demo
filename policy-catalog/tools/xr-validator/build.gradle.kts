plugins {
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("org.example.tap74.XrValidator")
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.networknt:json-schema-validator:2.0.0")
}

tasks.register<JavaExec>("runValidate") {
    group = "verification"
    description = "Runs the xr-validator validate command."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootProject.projectDir
    args("validate")
}

tasks.register<JavaExec>("runSemantic") {
    group = "verification"
    description = "Runs the xr-validator semantic command."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootProject.projectDir
    args("semantic")
}

tasks.register<JavaExec>("runSchemaCompat") {
    group = "verification"
    description = "Runs the xr-validator schema-compat command."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootProject.projectDir
    args("schema-compat")
}
