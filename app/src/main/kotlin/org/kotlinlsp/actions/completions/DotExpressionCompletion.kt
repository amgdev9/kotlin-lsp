package org.kotlinlsp.actions.completions

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaScopeKind
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance
import org.kotlinlsp.actions.completionKind
import org.kotlinlsp.common.info
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.db.Declaration
import org.kotlinlsp.index.queries.getCompletions

private val newlines = arrayOf("", "\n", "\n\n")
@OptIn(KaExperimentalApi::class)
fun autoCompletionDotExpression(ktFile: KtFile, offset: Int, index: Index, completingElement: KtDotQualifiedExpression, prefix: String, receiverType: String): List<CompletionItem> {
    val existingImports = ktFile.importList?.children?.filterIsInstance<KtImportDirective>() ?: emptyList()
    val (importInsertionOffset, newlineCount) = if (existingImports.isEmpty()) {
        ktFile.packageDirective?.textRange?.let { it.endOffset to 2 } ?: (ktFile.textRange.startOffset to 0)
    } else {
        existingImports.last().textRange.endOffset to 1
    }
    val importInsertionPosition = StringUtil.offsetToLineColumn(ktFile.text, importInsertionOffset).let { Position(it.line, it.column) }

    info("Completing dot expression at $offset with prefix '$prefix' and receiver type '$receiverType'")

    val completions = index.getCompletions(prefix) // TODO: ThisRef
        .filter {
            when (it) {
                is Declaration.Class -> false
                is Declaration.Function -> it.receiverFqName == receiverType
                is Declaration.Field -> it.parentFqName == receiverType
                is Declaration.EnumEntry -> false
            }
        }
        .mapNotNull { decl ->
            val additionalEdits = mutableListOf<TextEdit>()

            val detail = when (decl) {
                is Declaration.Class -> CompletionItemLabelDetails().apply {
                    detail = " (${decl.fqName})"
                }
                is Declaration.EnumEntry -> CompletionItemLabelDetails().apply {
                    detail = ": ${decl.enumFqName}"
                }
                is Declaration.Function -> CompletionItemLabelDetails().apply {
                    detail = "(${decl.parameters.joinToString(", ") { param -> "${param.name}: ${param.type}" }}): ${decl.returnType} (${decl.fqName})"
                }
                is Declaration.Field -> CompletionItemLabelDetails().apply {
                    detail = ": ${decl.type} (${decl.fqName})"
                }
            }

            val (inserted, insertionType) = when (decl) {
                is Declaration.Class -> decl.name to InsertTextFormat.PlainText
                is Declaration.EnumEntry -> "${decl.enumFqName.substringAfterLast('.')}.${decl.name}" to InsertTextFormat.PlainText
                is Declaration.Function -> "${decl.name}(${
                    decl.parameters.mapIndexed { index, param -> "\${${index+1}:${param.name}}" }.joinToString(", ")
                })" to InsertTextFormat.Snippet
                is Declaration.Field -> decl.name to InsertTextFormat.PlainText
            }

            CompletionItem().apply {
                label = decl.name
                labelDetails = detail
                kind = decl.completionKind()
                insertText = inserted
                insertTextFormat = insertionType
                additionalTextEdits = additionalEdits
            }
        }
        .toList()
    return completions
}