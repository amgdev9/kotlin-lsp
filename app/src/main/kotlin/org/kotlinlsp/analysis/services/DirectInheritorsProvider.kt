package org.kotlinlsp.analysis.services

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.containers.addIfNotNull
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.fir.utils.isSubclassOf
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionCache
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.contains
import org.jetbrains.kotlin.psi.psiUtil.getImportedSimpleNameByImportAlias
import org.jetbrains.kotlin.psi.psiUtil.getSuperNames
import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.analysis.modules.asFlatSequence
import org.kotlinlsp.common.profile
import org.kotlinlsp.index.Index

class DirectInheritorsProvider: KotlinDirectInheritorsProvider {
    private lateinit var index: Index
    private lateinit var modules: List<Module>
    private lateinit var project: Project

    private val classesBySupertypeName = mutableMapOf<Name, MutableSet<KtClassOrObject>>()
    private val inheritableTypeAliasesByAliasedName = mutableMapOf<Name, MutableSet<KtTypeAlias>>()

    fun setup(project: Project, index: Index, modules: List<Module>) {
        this.project = project
        this.index = index
        this.modules = modules
    }

    override fun getDirectKotlinInheritors(
        ktClass: KtClass,
        scope: GlobalSearchScope,
        includeLocalInheritors: Boolean
    ): Iterable<KtClassOrObject> = profile("getDirectKotlinInheritors", "$ktClass") {
        val classId = ktClass.getClassId() ?: return@profile emptyList()
        val baseModule = KotlinProjectStructureProvider.getModule(project, ktClass, useSiteModule = null)

        getDirectKotlinInheritorsByClassId(classId, baseModule, scope, includeLocalInheritors)
    }

    @OptIn(SymbolInternals::class)
    fun getDirectKotlinInheritorsByClassId(
        classId: ClassId,
        baseModule: KaModule,
        scope: GlobalSearchScope,
        includeLocalInheritors: Boolean
    ): Iterable<KtClassOrObject> = profile("getDirectKotlinInheritorsByClassId", "$classId") {
        computeIndex()

        val baseFirClass = classId.toFirSymbol(baseModule)?.fir as? FirClass ?: return@profile emptyList()

        val baseClassNames = mutableSetOf(classId.shortClassName)
        calculateAliases(classId.shortClassName, baseClassNames)

        val possibleInheritors = baseClassNames.flatMap { classesBySupertypeName[it].orEmpty() }
        if (possibleInheritors.isEmpty()) {
            return@profile emptyList()
        }
        possibleInheritors.filter { isValidInheritor(it, baseFirClass, scope, includeLocalInheritors) }
    }

    // Let's say this operation is not frequently called, if we discover it's not the case we should cache it
    private fun computeIndex() {
        classesBySupertypeName.clear()
        inheritableTypeAliasesByAliasedName.clear()

        modules.asFlatSequence()
            .filter { it.isSourceModule }
            .map { it.computeFiles() }
            .flatten()
            .mapNotNull { index.getKtFile(it) }
            .forEach {
                it.accept(object : KtTreeVisitorVoid() {
                    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                        classOrObject.getSuperNames().forEach { superName ->
                            classesBySupertypeName
                                .computeIfAbsent(Name.identifier(superName)) { mutableSetOf() }
                                .add(classOrObject)
                        }
                        super.visitClassOrObject(classOrObject)
                    }

                    override fun visitTypeAlias(typeAlias: KtTypeAlias) {
                        val typeElement = typeAlias.getTypeReference()?.typeElement ?: return

                        findInheritableSimpleNames(typeElement).forEach { expandedName ->
                            inheritableTypeAliasesByAliasedName
                                .computeIfAbsent(Name.identifier(expandedName)) { mutableSetOf() }
                                .add(typeAlias)
                        }

                        super.visitTypeAlias(typeAlias)
                    }
                })
            }
    }

    private fun calculateAliases(aliasedName: Name, aliases: MutableSet<Name>) {
        inheritableTypeAliasesByAliasedName[aliasedName].orEmpty().forEach { alias ->
            val aliasName = alias.nameAsSafeName
            val isNewAliasName = aliases.add(aliasName)
            if (isNewAliasName) {
                calculateAliases(aliasName, aliases)
            }
        }
    }

    @OptIn(KaImplementationDetail::class, SymbolInternals::class)
    private fun isValidInheritor(
        candidate: KtClassOrObject,
        baseFirClass: FirClass,
        scope: GlobalSearchScope,
        includeLocalInheritors: Boolean,
    ): Boolean {
        if (!includeLocalInheritors && candidate.isLocal) {
            return false
        }

        if (!scope.contains(candidate)) {
            return false
        }

        val candidateClassId = candidate.getClassId() ?: return false
        val candidateModule = KotlinProjectStructureProvider.getModule(project, candidate, useSiteModule = null)
        val candidateFirSymbol = candidateClassId.toFirSymbol(candidateModule) ?: return false
        val candidateFirClass = candidateFirSymbol.fir as? FirClass ?: return false

        return isSubclassOf(candidateFirClass, baseFirClass, candidateFirClass.moduleData.session, allowIndirectSubtyping = false)
    }

    @OptIn(LLFirInternals::class)
    private fun ClassId.toFirSymbol(module: KaModule): FirClassLikeSymbol<*>? {
        val session = LLFirSessionCache.getInstance(project).getSession(module, preferBinary = true)
        return session.symbolProvider.getClassLikeSymbolByClassId(this)
    }
}

private fun findInheritableSimpleNames(typeElement: KtTypeElement): List<String> {
    return when (typeElement) {
        is KtUserType -> {
            val referenceName = typeElement.referencedName ?: return emptyList()

            buildList {
                add(referenceName)

                val ktFile = typeElement.containingKtFile
                if (!ktFile.isCompiled) {
                    addIfNotNull(getImportedSimpleNameByImportAlias(typeElement.containingKtFile, referenceName))
                }
            }
        }
        is KtNullableType -> typeElement.innerType?.let(::findInheritableSimpleNames) ?: emptyList()
        else -> emptyList()
    }
}
