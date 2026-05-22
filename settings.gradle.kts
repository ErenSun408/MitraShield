pluginManagement {
    /*
     * 插件解析策略（国内网络 + Aliyun 镜像 + 上游 fallback）
     *   - dl.google.com 在此网络不可达（curl 15s timeout）
     *   - Aliyun `/google` 提供 AGP 8.7.3 ✓
     *   - Aliyun `/central` 提供 Kotlin / Hilt ✓
     *   - Aliyun 对 KSP plugin marker 返回 502（已踩过）
     *   - 所以 KSP 必须走 gradlePluginPortal 上游
     * 用 content 过滤器把每个插件 group 精确路由到能用的源，避免 502 污染解析链
     */
    repositories {
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
            content { includeGroupByRegex("com\\.android.*") }
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/central")
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin.*")
                includeGroupByRegex("com\\.google\\.dagger.*")
            }
        }
        // KSP + foojay convention + 任何上面没明确匹配的插件
        gradlePluginPortal()
        // 官方源 fallback（如果网络通的话）
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像（国内加速）
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        // 官方源（fallback）
        google()
        mavenCentral()
    }
}

rootProject.name = "MiDun"
include(":app")
