plugins {
    base
}

description = "TAP 7.4 policy catalog"

val buildOutDir = layout.buildDirectory.dir("out")
val artifactTag = providers.gradleProperty("ociArtifactTag").orElse("current")
val policiesArtifactName = "xregistry-policies"
val dataspaceArtifactName = "xregistry-dataspace-schemas"
val publishedDir = buildOutDir.map { it.dir("published") }

tasks.register("validateXRegistry") {
    group = "verification"
    description = "Runs the catalog validation entry point described in the specification."
    dependsOn(":tools:xr-validator:runValidate")
}

tasks.register("semanticTest") {
    group = "verification"
    description = "Runs the semantic checks described in the specification."
    dependsOn(":tools:xr-validator:runSemantic")
}

val preparePolicyArtifact by tasks.registering(Copy::class) {
    group = "build"
    description = "Stages the policy catalog files before packaging."
    from("src/main/xregistry")
    into(buildOutDir.map { it.dir("$policiesArtifactName-${artifactTag.get()}") })
}

val packageOciArtifact by tasks.registering(Tar::class) {
    group = "build"
    description = "Packages the policy catalog artifact structure."
    dependsOn(preparePolicyArtifact)
    archiveBaseName.set(policiesArtifactName)
    archiveVersion.set(artifactTag)
    archiveExtension.set("tar.gz")
    compression = Compression.GZIP
    destinationDirectory.set(buildOutDir)
    from(preparePolicyArtifact.map { it.destinationDir })
}

val prepareDataspaceArtifact by tasks.registering(Copy::class) {
    group = "build"
    description = "Stages the dataspace schema files before packaging."
    from("dataspace/src/main/xregistry")
    into(buildOutDir.map { it.dir("$dataspaceArtifactName-${artifactTag.get()}") })
}

val packageDataspaceArtifact by tasks.registering(Tar::class) {
    group = "build"
    description = "Packages the dataspace schema artifact structure."
    dependsOn(prepareDataspaceArtifact)
    archiveBaseName.set(dataspaceArtifactName)
    archiveVersion.set(artifactTag)
    archiveExtension.set("tar.gz")
    compression = Compression.GZIP
    destinationDirectory.set(buildOutDir)
    from(prepareDataspaceArtifact.map { it.destinationDir })
}

val publishOciArtifact by tasks.registering {
    group = "publishing"
    description = "Stages packaged catalog artifacts in a publish-ready local structure."
    dependsOn(packageOciArtifact, packageDataspaceArtifact)
    doLast {
        val publishRoot = publishedDir.get().asFile
        val tag = artifactTag.get()

        fun stageArtifact(artifactName: String, archiveFile: File) {
            val artifactDir = File(publishRoot, "$artifactName/$tag")
            artifactDir.mkdirs()

            val targetArchive = File(artifactDir, archiveFile.name)
            archiveFile.copyTo(targetArchive, overwrite = true)

            val metadata = File(artifactDir, "metadata.json")
            metadata.writeText(
                """
                {
                  "artifactName": "$artifactName",
                  "tag": "$tag",
                  "archiveFile": "${archiveFile.name}",
                  "sourceRepository": "tap74-policy-catalog"
                }
                """.trimIndent()
            )
        }

        stageArtifact(policiesArtifactName, packageOciArtifact.get().archiveFile.get().asFile)
        stageArtifact(dataspaceArtifactName, packageDataspaceArtifact.get().archiveFile.get().asFile)

        println("publishOciArtifact completed.")
        println("Local publish-ready artifacts written to: ${publishRoot.absolutePath}")
    }
}

tasks.register("buildXRegistryOciPublish") {
    group = "publishing"
    description = "Aggregates validation and packaging tasks for the catalog flow."
    dependsOn("validateXRegistry", "semanticTest", publishOciArtifact)
}

tasks.register("schemaCompat") {
    group = "verification"
    description = "Runs the schema compatibility checks described in the specification."
    dependsOn(":tools:xr-validator:runSchemaCompat")
}
