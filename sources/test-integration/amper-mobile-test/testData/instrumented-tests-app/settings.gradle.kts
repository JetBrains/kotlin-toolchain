pluginManagement {
    repositories {
        google()
        maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
    }
}

include(":app")
 