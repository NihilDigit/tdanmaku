// 全部源码在 commonMain。这不是"尽量兼容多平台"的目标,是硬约束:编排层(Danmaku.kt、
// DanmakuViewport.kt、DanmakuLayoutConfig.kt、DanmakuFlightPlan.kt、DanmakuScheduler.kt、
// DanmakuTimeline.kt、DanmakuCompiler.kt、ProcessingReport.kt、DanmakuHash.kt、
// DanmakuFrameRateCap.kt、SpecialDanmaku.kt)只依赖 kotlin stdlib,渲染层允许 compose-ui/
// foundation 与协程。任何平台专有 API 进来都会在 commonMain 编译期被挡住,不需要人去 review。
//
// 不引 material3:库不带主题,外观全部来自调用方传进来的 DanmakuRenderStyle。
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
    signing
}

group = "dev.nihildigit"

val PROJECT_URL = "https://github.com/NihilDigit/tdanmaku"
version = "0.2.0"

kotlin {
    android {
        namespace = "dev.nihildigit.danmaku"
        compileSdk = 37
        minSdk = 29
    }

    jvm()

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api 而非 implementation:这三样的类型出现在公开签名里 —— DanmakuHost 是
            // @Composable(runtime),参数有 Modifier、DanmakuRenderStyle 里有 Color/TextStyle
            // (ui)。用 implementation 的话消费方拿不到这些类型的编译期可见性。
            api(libs.compose.runtime)
            api(libs.compose.ui)

            // foundation 只在内部用(Canvas、Box、size),没有一个 foundation 类型出现在公开
            // 签名里,所以留在 implementation。协程同理:suspend 是 stdlib 的事,Channel 和
            // withTimeoutOrNull 都在 private 实现里。
            implementation(libs.compose.foundation)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// ---- 发布 ----
//
// KMP 的 maven-publish 已经替每个 target 建好了 publication,并且自带 sources jar。
// 这里只补 Central 要求、而它不生成的两样:javadoc 制品和 POM 元数据。

/**
 * 空的 javadoc jar。Central 强制要求这个制品存在,但 Kotlin 的文档载体是源码和 KDoc,
 * 生成一份 Java 视角的 API 文档既不准确也没人看。真要文档时该上 Dokka,而不是让这里
 * 产出一堆误导性的 HTML。
 */
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

/**
 * Central Portal 收的是一个按 Maven 仓库目录结构打好的 zip,不是 `deploy` 到某个 URL。所以这里
 * 的"仓库"是构建目录下的一个文件夹,[centralBundle] 再把它压起来 —— 上传动作留给人,凭据不进
 * 构建脚本。
 */
val centralBundleDir = layout.buildDirectory.dir("central-bundle")

publishing {
    repositories {
        maven {
            name = "centralBundle"
            url = uri(centralBundleDir)
        }
    }
    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)
        pom {
            name.set("tdanmaku")
            description.set(
                "Deterministic danmaku engine for Compose Multiplatform: positions are a pure " +
                    "function of playback time, so seeking is a query rather than a replay.",
            )
            url.set(PROJECT_URL)
            licenses {
                license {
                    name.set("GNU General Public License v3.0")
                    url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("NihilDigit")
                    url.set("https://github.com/NihilDigit")
                }
            }
            scm {
                url.set(PROJECT_URL)
                connection.set("scm:git:$PROJECT_URL.git")
                developerConnection.set("scm:git:ssh://git@github.com/NihilDigit/tdanmaku.git")
            }
        }
    }
}

signing {
    // 密钥从 Gradle 属性来。本地放 `~/.gradle/gradle.properties`(在仓库之外,不会被顺手
    // commit,也不进 shell history);CI 上同一个属性用 ORG_GRADLE_PROJECT_signingKey
    // 这个环境变量喂进来,构建脚本这边不用分叉。
    //
    // 没配置时不注册签名任务 —— `./gradlew build` 在任何机器上都能跑,只有真要发布才需要密钥。
    val key = providers.gradleProperty("signingKey").orNull
    // 口令传空串而不是 null:无口令的密钥在 null 下构造不出签名者,报的是
    // "no configured signatory",看不出跟口令有关。
    val password = providers.gradleProperty("signingPassword").orNull.orEmpty()
    if (key.isNullOrBlank()) return@signing
    useInMemoryPgpKeys(key, password)

    // **要等到 afterEvaluate。** KMP 插件是在那时才为每个 target 建 publication 的,在配置
    // 阶段直接 sign(publishing.publications) 拿到的是一个空容器 —— 不报错,只是一个签名任务
    // 都不生成,而这件事要到 Central 拒收才会被发现。
    afterEvaluate { sign(publishing.publications) }
}

// 签名任务和发布任务之间没有隐式依赖:Gradle 只知道发布要用到那些 .asc 文件所在的目录,
// 不知道是谁产出的。缺了这一条,并行构建下会出现"发布跑在签名之前"。
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(tasks.withType<Sign>())
}

// 上一次发版留下的文件还在这个目录里,不清就会被打进这次的 zip,Portal 那边表现为"这个版本
// 里混进了别的版本的制品"。
//
// 清理挂在每个发布任务上,不挂在聚合任务上:`dependsOn` 只保证跑在聚合任务之前,不保证跑在
// 它自己那些依赖之前,那样清理会插在两次发布中间,把先产出的制品删掉。
val cleanCentralBundle by tasks.registering(Delete::class) {
    delete(centralBundleDir)
}

tasks.withType<PublishToMavenRepository>().configureEach {
    if (repository?.name == "centralBundle") dependsOn(cleanCentralBundle)
}

/**
 * 待上传的包。`./gradlew centralBundle` 之后把 `build/tdanmaku-<版本>-bundle.zip` 拖进
 * Central Portal 的 Publish Component。
 *
 * maven-metadata.xml 排除掉:那是仓库级的索引,由 Central 自己维护,混在制品里会被判成多余文件。
 */
val centralBundle by tasks.registering(Zip::class) {
    dependsOn("publishAllPublicationsToCentralBundleRepository")
    from(centralBundleDir) { exclude("**/maven-metadata.xml*") }
    archiveFileName.set("tdanmaku-$version-bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
}
