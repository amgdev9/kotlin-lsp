package org.kotlinlsp.actions

import com.intellij.psi.util.leavesAroundOffset
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.kotlinlsp.actions.completions.autoCompletionDotExpression
import org.kotlinlsp.actions.completions.autoCompletionGeneric
import org.kotlinlsp.common.info
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.db.Declaration


@OptIn(KaExperimentalApi::class)
fun autocompleteAction(ktFile: KtFile, offset: Int, index: Index): List<CompletionItem> {
    val leaf = ktFile.leavesAroundOffset(offset).asSequence()
        .toList().last().first

    val prefix = leaf.text.substring(0, offset - leaf.textRange.startOffset)
    val completingElement = leaf.parentOfType<KtElement>() ?: ktFile

    info("Completing at $offset with prefix '$prefix', element type: ${completingElement.javaClass.simpleName}")

    if (completingElement is KtNameReferenceExpression) {
        if (completingElement.parent is KtDotQualifiedExpression) {
            return completeDotQualified(ktFile, offset, index, completingElement.parent as KtDotQualifiedExpression, prefix)
        } else {
            return autoCompletionGeneric(ktFile, offset, index, completingElement, prefix)
        }
    }

    if (completingElement is KtDotQualifiedExpression) {
        assert(prefix == ".")
        return completeDotQualified(ktFile, offset, index, completingElement, "")
    }

    if (completingElement is KtValueArgumentList) {
        return emptyList() // TODO: function call arguments
    }

    return autoCompletionGeneric(ktFile, offset, index, completingElement, prefix)
}

private fun completeDotQualified(
    ktFile: KtFile,
    offset: Int,
    index: Index,
    completingElement: KtDotQualifiedExpression,
    prefix: String
): List<CompletionItem> {
    val receiver = analyze(completingElement) {
        completingElement.receiverExpression.expressionType.toString()
    }
    return autoCompletionDotExpression(
        ktFile,
        offset,
        index,
        completingElement,
        prefix,
        receiver
    )
}

fun Declaration.completionKind(): CompletionItemKind =
    when (this) {
        is Declaration.EnumEntry -> CompletionItemKind.EnumMember
        is Declaration.Class -> when (this.type) {
            Declaration.Class.Type.CLASS -> CompletionItemKind.Class
            Declaration.Class.Type.ABSTRACT_CLASS -> CompletionItemKind.Class
            Declaration.Class.Type.INTERFACE -> CompletionItemKind.Interface
            Declaration.Class.Type.ENUM_CLASS -> CompletionItemKind.Enum
            Declaration.Class.Type.OBJECT -> CompletionItemKind.Module
            Declaration.Class.Type.ANNOTATION_CLASS -> CompletionItemKind.Interface
        }
        is Declaration.Function -> CompletionItemKind.Function
        is Declaration.Field -> CompletionItemKind.Field
    }

