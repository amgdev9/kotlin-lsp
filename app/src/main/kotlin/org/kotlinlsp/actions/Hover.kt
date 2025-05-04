package org.kotlinlsp.actions

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiFormatUtil
import com.intellij.psi.util.PsiFormatUtilBase.*
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.common.getElementRange
import org.kotlinlsp.common.toOffset

enum class Language {
    KOTLIN, JAVA
}

@OptIn(KaExperimentalApi::class)
private val renderer = KaDeclarationRendererForSource.WITH_SHORT_NAMES

@OptIn(KaExperimentalApi::class)
fun hoverAction(ktFile: KtFile, position: Position): Triple<String, Range, Language>? {
    val offset = position.toOffset(ktFile)
    val ktElement = ktFile.findElementAt(offset)?.parentOfType<KtElement>() ?: return null
    val range = getElementRange(ktFile, ktElement)

    val ktDeclaration =
        (ktElement as? KtDeclaration)
            ?: (ktFile.findReferenceAt(offset)?.resolve() as? KtDeclaration)
    if (ktDeclaration != null) {
        val text = analyze(ktDeclaration) {
            ktDeclaration.symbol.render(renderer)
        }
        return Triple(text, range, Language.KOTLIN)
    }

    val javaDeclaration =
        (ktFile.findReferenceAt(offset)?.resolve() as? PsiNamedElement)
            ?: return null
    val text =
        when (javaDeclaration) {
            is PsiMethod ->
                PsiFormatUtil.formatMethod(
                    javaDeclaration,
                    PsiSubstitutor.EMPTY,
                    SHOW_NAME or SHOW_TYPE or SHOW_PARAMETERS,
                    SHOW_NAME or SHOW_TYPE,
                )

            is PsiClass ->
                PsiFormatUtil.formatClass(
                    javaDeclaration,
                    SHOW_NAME or SHOW_TYPE,
                )

            is PsiVariable ->
                PsiFormatUtil.formatVariable(
                    javaDeclaration,
                    SHOW_NAME or SHOW_TYPE,
                    PsiSubstitutor.EMPTY
                )

            else -> return null
        }
    return Triple(text, range, Language.JAVA)
}
