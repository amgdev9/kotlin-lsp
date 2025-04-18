package org.kotlinlsp.analysis.services

import com.intellij.mock.MockProject
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.platform.mergeSpecificProviders
import org.jetbrains.kotlin.analysis.api.platform.packages.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.kotlinlsp.analysis.services.utils.virtualFilesForPackage
import org.kotlinlsp.utils.trace

class PackageProviderFactory: KotlinPackageProviderFactory {
    private lateinit var project: MockProject

    fun setup(project: MockProject) {
        this.project = project
    }

    override fun createPackageProvider(searchScope: GlobalSearchScope): KotlinPackageProvider = PackageProvider(project, searchScope)
}

private class PackageProvider(project: Project, searchScope: GlobalSearchScope): KotlinPackageProviderBase(project, searchScope) {
    override fun doesKotlinOnlyPackageExist(packageFqName: FqName): Boolean {
        trace("doesKotlinOnlyPackageExist: $packageFqName")

        return packageFqName.isRoot || virtualFilesForPackage(project, searchScope, packageFqName).iterator().hasNext()
    }

    override fun getKotlinOnlySubpackageNames(packageFqName: FqName): Set<Name> {
        trace("[X] getKotlinOnlySubpackageNames: $packageFqName")
        return emptySet()
    }
}

class PackageProviderMerger(private val project: Project) : KotlinPackageProviderMerger {
    override fun merge(providers: List<KotlinPackageProvider>): KotlinPackageProvider =
        providers.mergeSpecificProviders<_, PackageProvider>(KotlinCompositePackageProvider.factory) { targetProviders ->
            val combinedScope = GlobalSearchScope.union(targetProviders.map { it.searchScope })
            project.createPackageProvider(combinedScope).apply {
                check(this is PackageProvider) {
                    "`${PackageProvider::class.simpleName}` can only be merged into a combined package provider of the same type."
                }
            }
        }
}
