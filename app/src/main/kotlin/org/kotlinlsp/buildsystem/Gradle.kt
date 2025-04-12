package org.kotlinlsp.buildsystem

import com.intellij.mock.MockProject
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.impl.base.util.LibraryUtils
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.kotlinlsp.analysis.services.modules.LibraryModule
import org.kotlinlsp.analysis.services.modules.SourceModule
import org.kotlinlsp.trace
import org.kotlinlsp.printModule
import java.io.File
import kotlin.io.path.Path

private var cachedModule: KaModule? = null

@OptIn(KaImplementationDetail::class)
fun getModuleList(project: MockProject, appEnvironment: KotlinCoreApplicationEnvironment, rootPath: String): KaModule {
    val constantCachedModule = cachedModule
    if(constantCachedModule != null) return constantCachedModule

    val connection = GradleConnector.newConnector()
        .forProjectDirectory(File(rootPath))
        .connect()

    val model = connection.model(IdeaProject::class.java)

    // TODO: We can report progress here to the LSP client
    model.addProgressListener({ trace("[GRADLE] ${it.displayName}") }, OperationType.PROJECT_CONFIGURATION)

    val ideaProject = model.get()

    val jvmTarget = checkNotNull(JvmTarget.fromString(ideaProject.jdkName)) { "Unknown jdk target" }
    val jdkModule = getJdkHomeFromSystemProperty()?.let { jdkHome ->
        LibraryModule(
            appEnvironment = appEnvironment,
            mockProject = project,
            roots = LibraryUtils.findClassesFromJdkHome(jdkHome.toPath(), isJre = false),
            javaVersion = jvmTarget,
            isJdk = true,
            name = "JDK"
        )
    }

    val kotlinStdlib = LibraryModule(
        appEnvironment = appEnvironment,
        mockProject = project,
        name = "Kotlin stdlib",
        javaVersion = jvmTarget,
        roots = listOf(Path(getKotlinJvmStdlibJarPath())),
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

        SourceModule(
            mockProject = project,
            folderPath = module.contentRoots.first().rootDirectory.path,
            dependencies = listOfNotNull(jdkModule, kotlinStdlib) + dependencies,
            javaVersion = jvmTarget,
            kotlinVersion = LanguageVersion.KOTLIN_2_1,
            moduleName = module.name
        )
    }

    // TODO Support multiple modules, for now take the last one
    val rootModule = modules.last()
    printModule(rootModule)
    cachedModule = rootModule
    return rootModule
}

private fun getJdkHomeFromSystemProperty(): File? {
    val javaHome = File(System.getProperty("java.home"))
    if (!javaHome.exists()) {
        return null
    }
    return javaHome
}

internal fun getKotlinJvmStdlibJarPath(): String {
    return lazyKotlinJvmStdlibJar
}

private val lazyKotlinJvmStdlibJar by lazy {
    ClassLoader.getSystemResource("kotlin/jvm/Strictfp.class")
        ?.file
        ?.replace("file:", "")
        ?.replaceAfter(".jar", "")
        ?: error("Unable to find Kotlin's JVM stdlib jar.")
}

