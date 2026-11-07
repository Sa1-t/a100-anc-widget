pluginManagement {
    repositories {
        // NOTE: dl.google.com is unreachable from Gradle's HTTP client through
        // this machine's egress proxy (TLS handshake to dl.google.com is cut
        // off right after the server certificate; curl works, Java does not).
        // Use the Aliyun mirror of Google Maven instead. The AGP plugin and
        // all com.android.* artifacts live here.
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        mavenCentral()
    }
}
rootProject.name = "anc-widget"
include(":app")
