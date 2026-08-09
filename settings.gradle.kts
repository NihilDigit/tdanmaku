pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Central 没有官方 Gradle 插件(官方文档明说这一点),nmcp 只做一件事:把 maven-publish
// 产出的制品打成 bundle 传给 Portal。签名和 POM 仍归 build.gradle.kts 里的
// signing/maven-publish 管,看得见。
//
// 版本号写字面量:settings 的 plugins 块里拿不到版本目录访问器(目录本身就在这个文件里定义)。
plugins {
    id("com.gradleup.nmcp.settings") version "1.6.1"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

// 单工程构建:根工程本身就是这个库。消费方通过 composite build(`includeBuild`)引入时,
// Gradle 按 `group:rootProject.name` 自动做依赖替换,不需要在消费侧写 dependencySubstitution。
rootProject.name = "tdanmaku"

nmcpSettings {
    centralPortal {
        // 凭据从 Gradle 属性来,和签名密钥同一套:本地放 `~/.gradle/gradle.properties`,
        // CI 上用 ORG_GRADLE_PROJECT_centralUsername 这类环境变量。
        //
        // 读成字符串而不是把 provider 传进去:nmcp 会把这个块塞进一个 GradleLifecycle 隔离
        // 动作,provider 那条路在 configuration cache 下序列化不了(报 "cannot serialize
        // object of type ValueSourceProvider")。
        username = providers.gradleProperty("centralUsername").orNull.orEmpty()
        password = providers.gradleProperty("centralPassword").orNull.orEmpty()
        // 上传之后停在门户里等人点发布,不自动放行 —— 发出去的版本撤不回来。
        publishingType = "USER_MANAGED"
    }
}
