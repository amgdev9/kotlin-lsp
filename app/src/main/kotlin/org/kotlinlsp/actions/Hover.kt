package org.kotlinlsp.actions

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

@OptIn(KaExperimentalApi::class)
private val renderer = KaDeclarationRendererForSource.WITH_SHORT_NAMES

@OptIn(KaExperimentalApi::class)
fun hoverAction(ktFile: KtFile, position: Position): Pair<String, Range>? {
    val offset = position.toOffset(ktFile)
    val ktElement = ktFile.findElementAt(offset)?.parentOfType<KtElement>() ?: return null
    val range = getElementRange(ktFile, ktElement)

    val declaration =
        (ktElement as? KtDeclaration)
            ?: (ktFile.findReferenceAt(offset)?.resolve() as? KtDeclaration)
            ?: return null

    val text = analyze(declaration) {
        declaration.symbol.render(renderer)
    }

    return Pair(text, range)
}
