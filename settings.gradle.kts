pluginManagement {
    repositories {
        val mirrorUrl = System.getenv("PGPE_MAVEN_REPO_URL")
        val mirrorUser = System.getenv("PGPE_MAVEN_REPO_USER")
        val mirrorPassword = System.getenv("PGPE_MAVEN_REPO_PASSWORD")

        if (!mirrorUrl.isNullOrBlank()) {
            maven {
                url = uri(mirrorUrl)
                if (!mirrorUser.isNullOrBlank() && !mirrorPassword.isNullOrBlank()) {
                    credentials {
                        username = mirrorUser
                        password = mirrorPassword
                    }
                }
            }
        }

        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        val mirrorUrl = System.getenv("PGPE_MAVEN_REPO_URL")
        val mirrorUser = System.getenv("PGPE_MAVEN_REPO_USER")
        val mirrorPassword = System.getenv("PGPE_MAVEN_REPO_PASSWORD")

        if (!mirrorUrl.isNullOrBlank()) {
            maven {
                url = uri(mirrorUrl)
                if (!mirrorUser.isNullOrBlank() && !mirrorPassword.isNullOrBlank()) {
                    credentials {
                        username = mirrorUser
                        password = mirrorPassword
                    }
                }
            }
        }

        mavenCentral()
    }
}

rootProject.name = "pg-policy-engine"
