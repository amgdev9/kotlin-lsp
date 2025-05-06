package org.kotlinlsp.actions

import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.successfulConstructorCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.kotlinlsp.common.getElementRange
import org.kotlinlsp.common.toOffset

@OptIn(KaExperimentalApi::class)
private val renderer = KaDeclarationRendererForSource.WITH_SHORT_NAMES

@OptIn(KaExperimentalApi::class)
fun hoverAction(ktFile: KtFile, position: Position): Pair<String, Range>? {
    val offset = position.toOffset(ktFile)
    val ktElement = ktFile.findElementAt(offset)?.parentOfType<KtElement>() ?: return null
    val range = getElementRange(ktFile, ktElement)

    val text =
        (ktElement as? KtDeclaration ?: ktFile.findReferenceAt(offset)?.resolve() as? KtDeclaration)?.let {
            analyze(it) {
                it.symbol.render(renderer)
            }
        } ?: ktElement.parentOfType<KtReferenceExpression>()?.let {
            analyze(it) {
                val call = it.resolveToCall()
                val successfulCall =
                    call?.successfulFunctionCallOrNull()
                        ?: call?.successfulConstructorCallOrNull()
                        ?: call?.successfulVariableAccessCall()
                        ?: return null
                val symbol = successfulCall.symbol
                symbol.render(renderer)
            }
        } ?: return null
    return Pair(text, range)
}
