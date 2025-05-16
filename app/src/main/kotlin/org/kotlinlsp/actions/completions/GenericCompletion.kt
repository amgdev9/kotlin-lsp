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
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance
import org.kotlinlsp.actions.completionKind
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.db.Declaration
import org.kotlinlsp.index.queries.getCompletions

private val newlines = arrayOf("", "\n", "\n\n")
@OptIn(KaExperimentalApi::class)
fun autoCompletionGeneric(ktFile: KtFile, offset: Int, index: Index, completingElement: KtElement, prefix: String): List<CompletionItem> {
    val localVariableCompletions: List<CompletionItem> = analyze(completingElement) {
        ktFile.scopeContext(completingElement).scopes.flatMap {
            if (it.kind !is KaScopeKind.LocalScope) return@flatMap emptyList()
            it.scope.declarations.mapNotNull { decl ->
                if (!decl.name.toString().startsWith(prefix)) return@mapNotNull null
                val psi = decl.psi ?: return@mapNotNull null
                // TODO: This is a hack to get the correct offset for function literals, can analysis tell us if a declaration is accessible?
                val declOffset = if (psi is KtFunctionLiteral) psi.textRange.startOffset else psi.textRange.endOffset
                if (declOffset >= offset) return@mapNotNull null

                val detail = when (decl) {
                    is KaVariableSymbol -> decl.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
                    else -> "Missing ${decl.javaClass.simpleName}"
                }

                val preview = when (psi) {
                    is KtProperty -> psi.text
                    is KtParameter -> {
                        if (psi.isLoopParameter) {
                            val loop = psi.parentOfType<KtLoopExpression>()!!
                            loop.text.replace(loop.body!!.text, "")
                        } else psi.text
                    }
                    is KtFunctionLiteral -> decl.name // TODO: Show the function call containing the lambda?
                    else -> "TODO: Preview for ${psi.javaClass.simpleName}"
                }

                CompletionItem().apply {
                    label = decl.name.toString()
                    labelDetails = CompletionItemLabelDetails().apply {
                        this.detail = "  $detail"
                        description = ""
                    }
                    documentation = Either.forRight(
                        MarkupContent("markdown", "```kotlin\n${preview}\n```")
                    )
                    kind = CompletionItemKind.Variable
                    insertText = decl.name.toString()
                    insertTextFormat = InsertTextFormat.PlainText
                }
            }.toList()
        }
    }

    val existingImports = ktFile.importList?.children?.filterIsInstance<KtImportDirective>() ?: emptyList()
    val (importInsertionOffset, newlineCount) = if (existingImports.isEmpty()) {
        ktFile.packageDirective?.textRange?.let { it.endOffset to 2 } ?: (ktFile.textRange.startOffset to 0)
    } else {
        existingImports.last().textRange.endOffset to 1
    }
    val importInsertionPosition = StringUtil.offsetToLineColumn(ktFile.text, importInsertionOffset).let { Position(it.line, it.column) }

    val completions = index.getCompletions(prefix) // TODO: ThisRef
        .filter {
            when (it) {
                is Declaration.Class -> true
                is Declaration.Field -> it.static
                is Declaration.Function -> it.static
                is Declaration.EnumEntry -> true
            }
        }
        .mapNotNull { decl ->
            val additionalEdits = mutableListOf<TextEdit>()

            if (decl is Declaration.Class) {
                val exists = existingImports.any {
                    it.importedFqName?.asString() == decl.fqName
                }
                if (!exists) {
                    val importText = "import ${decl.fqName}"
                    val edit = TextEdit().apply {
                        range = Range(importInsertionPosition, importInsertionPosition)
                        newText = "${newlines[newlineCount]}$importText"
                    }
                    additionalEdits.add(edit)
                }
            }

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
    return localVariableCompletions + completions
}