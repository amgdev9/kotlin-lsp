package org.kotlinlsp.actions

import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.common.getElementRange
import org.kotlinlsp.common.toOffset

@OptIn(KaExperimentalApi::class)
private val renderer = KaDeclarationRendererForSource.WITH_SHORT_NAMES

@OptIn(KaExperimentalApi::class)
fun hoverAction(ktFile: KtFile, position: Position): Pair<String, Range>? {
    val offset = position.toOffset(ktFile)
    val ktElement = ktFile.findElementAt(offset)?.parentOfType<KtElement>() ?: return null
    val range = getElementRange(ktFile, ktElement)
    val text = analyze(ktElement) {
        val symbol =
            if (ktElement is KtDeclaration) ktElement.symbol
            else ktElement.mainReference?.resolveToSymbol() as? KaDeclarationSymbol ?: return null
        symbol.render(renderer)
    }
    return Pair(text, range)
}
