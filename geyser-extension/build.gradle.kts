dependencies {
    compileOnly("org.geysermc.geyser:api:${property("geyserVersion")}")
    implementation(project(":common"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("extension.yml") { expand(props) }
}

tasks.jar {
    archiveBaseName.set("SimplexGeyserExtension")
    archiveClassifier.set("")
    dependsOn(":common:jar")
    from(project(":common").sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
