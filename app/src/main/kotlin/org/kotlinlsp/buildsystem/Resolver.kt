package org.kotlinlsp.buildsystem

import com.intellij.mock.MockProject
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.kotlinlsp.common.profile
import java.io.File

class BuildSystemResolver(
    project: MockProject,
    appEnvironment: KotlinCoreApplicationEnvironment,
    rootFolder: String
) {
    private val BUILD_SYSTEMS: List<BuildSystem> = listOf(
        FileBasedBuildSystem(project, appEnvironment, rootFolder),
        GradleBuildSystem(project, appEnvironment, rootFolder)
    )

    fun resolveModuleDAG(): KaModule = profile("BuildSystemResolver", "") {
        // TODO Implement build system result caching
        BUILD_SYSTEMS.forEach {
            if(it.markerFiles.any { File(it).exists() }) {
                return@profile it.resolveRootModuleIfNeeded(null)!!.first
            }
        }
        throw Exception("Not suitable build system found!")
    }
}
