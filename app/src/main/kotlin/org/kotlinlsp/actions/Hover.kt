package org.kotlinlsp.actions

import com.intellij.lang.jvm.JvmClassKind
import com.intellij.lang.jvm.JvmModifier
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiVariable
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

    val ktDeclaration =
        (ktElement as? KtDeclaration)
            ?: (ktFile.findReferenceAt(offset)?.resolve() as? KtDeclaration)
    if (ktDeclaration != null) {
        val text = analyze(ktDeclaration) {
            ktDeclaration.symbol.render(renderer)
        }
        return Pair(text, range)
    }

    val javaDeclaration =
        (ktFile.findReferenceAt(offset)?.resolve() as? PsiNamedElement)
            ?: return null
    val text = when (javaDeclaration) {
        is PsiMethod -> buildString {
            append("fun ")

            javaDeclaration.typeParameters.takeIf { it.isNotEmpty() }?.let {
                append("<")
                append(it.joinToString(", ") { it.name.toString() })
                append("> ")
            }

            append(javaDeclaration.name)
            append("(")
            append(javaDeclaration.parameterList.parameters.joinToString(", ") {
                "${it.name}: ${it.type.presentableText}"
            })
            append(")")

            javaDeclaration.returnType?.takeIf { it.presentableText != "void" }?.let {
                append(": ${it.presentableText}")
            }
        }

        is PsiClass -> buildString {
            when (javaDeclaration.classKind) {
                JvmClassKind.CLASS -> append("class ")
                JvmClassKind.INTERFACE -> append("interface ")
                JvmClassKind.ANNOTATION -> append("annotation class ")
                JvmClassKind.ENUM -> append("enum class ")
            }
            append(javaDeclaration.name)

            javaDeclaration.typeParameters.takeIf { it.isNotEmpty() }?.let {
                append("<")
                append(it.joinToString(", ") { it.name.toString() })
                append(">")
            }

            javaDeclaration.extendsList?.referencedTypes?.takeIf { it.isNotEmpty() }?.let {
                append(" : ")
                append(it.joinToString(", ") { it.presentableText })
            }
        }

        is PsiVariable -> buildString {
            if (javaDeclaration.hasModifier(JvmModifier.FINAL)) {
                append("val ")
            } else {
                append("var ")
            }
            append(javaDeclaration.name)
            append(": ")
            append(javaDeclaration.type.presentableText)
        }

        else -> return null
    }
    return Pair(text, range)
}
