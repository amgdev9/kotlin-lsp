package org.kotlinlsp.buildsystem

import com.intellij.mock.MockProject
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.kotlinlsp.analysis.services.modules.LibraryModule
import org.kotlinlsp.analysis.services.modules.SourceModule
import org.kotlinlsp.common.debug
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path

class GradleBuildSystem(
    private val project: MockProject,
    private val appEnvironment: KotlinCoreApplicationEnvironment,
    private val rootFolder: String
) : BuildSystem {
    override val markerFiles: List<String> = listOf(
        "$rootFolder/build.gradle", "$rootFolder/build.gradle.kts",
        "$rootFolder/settings.gradle", "$rootFolder/settings.gradle.kts",
    )

    @OptIn(KaImplementationDetail::class)
    override fun resolveRootModuleIfNeeded(cachedVersion: String?): Pair<KaModule, String>? {
        val connection = GradleConnector.newConnector()
            .forProjectDirectory(File(rootFolder))
            .connect()

        val model = connection.model(IdeaProject::class.java)

        model.addProgressListener({ debug("[GRADLE] ${it.displayName}") }, OperationType.PROJECT_CONFIGURATION)

        val ideaProject = model.get()

        val jvmTarget = checkNotNull(JvmTarget.fromString(ideaProject.jdkName)) { "Unknown jdk target" }
        val jdkModule = getJdkHomePathFromSystemProperty()?.let { jdkHome ->
            LibraryModule(
                appEnvironment = appEnvironment,
                mockProject = project,
                roots = listOf(jdkHome),
                javaVersion = jvmTarget,
                isJdk = true,
                name = "JDK ${jvmTarget.description}"
            )
        }

        val kotlinStdlib = LibraryModule(
            appEnvironment = appEnvironment,
            mockProject = project,
            name = "Kotlin stdlib",
            javaVersion = jvmTarget,
            roots = listOf(getKotlinJvmStdlibJarPath()),
        )


        val modules = ideaProject.modules.map { module ->
            val dependencies = module
                .dependencies
                .filterIsInstance<IdeaSingleEntryLibraryDependency>()
                .map { dependency ->
                    LibraryModule(
                        appEnvironment = appEnvironment,
                        mockProject = project,
                        name = dependency.file.name,
                        javaVersion = jvmTarget,
                        roots = listOf(dependency.file.toPath()),
                    )
                }


            val allDependencies = mutableListOf<KaModule>(kotlinStdlib)
            if (jdkModule != null) {
                allDependencies.add(jdkModule)
            }
            allDependencies.addAll(dependencies)

            SourceModule(
                mockProject = project,
                folderPath = module.contentRoots.first().rootDirectory.path,
                dependencies = allDependencies,
                javaVersion = jvmTarget,
                kotlinVersion = LanguageVersion.KOTLIN_2_1,
                moduleName = module.name
            )
        }

        // TODO Support multiple modules, for now take the last one
        val rootModule = modules.last()
        return rootModule to ""
    }
}

private fun getJdkHomePathFromSystemProperty(): Path? {
    val javaHome = File(System.getProperty("java.home"))
    if (!javaHome.exists()) {
        return null
    }
    return javaHome.toPath()
}

internal fun getKotlinJvmStdlibJarPath(): Path {
    return Path(lazyKotlinJvmStdlibJar)
}

private val lazyKotlinJvmStdlibJar by lazy {
    ClassLoader.getSystemResource("kotlin/jvm/Strictfp.class")
        ?.file
        ?.replace("file:", "")
        ?.replaceAfter(".jar", "")
        ?: error("Unable to find Kotlin's JVM stdlib jar.")
}
