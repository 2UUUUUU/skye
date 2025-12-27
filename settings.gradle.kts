pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "skye"

includeBuild("meteorclient") {
    dependencySubstitution {
        substitute(module("meteordevelopment:meteor-client")).using(project(":"))
    }
}
