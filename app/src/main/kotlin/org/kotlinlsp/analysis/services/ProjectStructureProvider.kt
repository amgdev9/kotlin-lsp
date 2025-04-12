package org.kotlinlsp.analysis.services

import com.intellij.mock.MockProject
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProviderBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.kotlinlsp.buildsystem.getModuleList
import org.kotlinlsp.trace

class ProjectStructureProvider: KotlinProjectStructureProviderBase() {
    private lateinit var mockProject: MockProject
    private lateinit var appEnvironment: KotlinCoreApplicationEnvironment
    private lateinit var rootPath: String

    fun setup(project: MockProject, applicationEnvironment: KotlinCoreApplicationEnvironment, rootPath: String) {
        this.mockProject = project
        this.appEnvironment = applicationEnvironment
        this.rootPath = rootPath
    }

    private val rootModule: KaModule by lazy {
        getModuleList(mockProject, appEnvironment, rootPath)
    }

    override fun getImplementingModules(module: KaModule): List<KaModule> {
        trace("getImplementingModules: $module")
        return emptyList()  // TODO
    }

    override fun getModule(element: PsiElement, useSiteModule: KaModule?): KaModule {
        trace("getModule: $element, useSiteModule: $useSiteModule")
        val virtualFile = element.containingFile.virtualFile
        return searchVirtualFileInModule(virtualFile, useSiteModule ?: rootModule)!!
    }

    private fun searchVirtualFileInModule(virtualFile: VirtualFile, module: KaModule): KaModule? {
        if(module.contentScope.contains(virtualFile)) return module

        for(it in module.directRegularDependencies) {
            val submodule = searchVirtualFileInModule(virtualFile, it)
            if(submodule != null) return submodule
        }
        return null
    }

    @OptIn(KaPlatformInterface::class)
    override fun getNotUnderContentRootModule(project: Project): KaNotUnderContentRootModule {
        trace("getNotUnderContentRootModule")
        throw Exception("unsupported")
    }
}
