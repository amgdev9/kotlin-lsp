package org.kotlinlsp.common

import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.analysis.modules.LibraryModule
import org.kotlinlsp.analysis.modules.SourceModule
import org.kotlinlsp.analysis.modules.Module
import java.io.*
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.io.path.absolutePathString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

private enum class LogLevel(level: Int) {
    Trace(0),
    Debug(1),
    Info(2),
    Warning(3),
    Error(4),
    Off(5)
}

// Configure as needed
private val logLevel = LogLevel.Debug
private const val profileEnabled = true

private lateinit var logger: LSPLogger
private val profileInfo = mutableMapOf<String, Pair<Int, Duration>>()

private class LSPLogger(val client: LanguageClient) {
    fun log(level: LogLevel, message: String) {
        if (level < logLevel) return

        val type = when (level) {
            LogLevel.Trace -> MessageType.Log
            LogLevel.Debug -> MessageType.Log
            LogLevel.Info -> MessageType.Info
            LogLevel.Warning -> MessageType.Warning
            LogLevel.Error -> MessageType.Error
            LogLevel.Off -> return
        }

        client.logMessage(MessageParams(type, message))
    }

    fun redirectSystemErr() {
        val loggerStream = PrintStream(object : OutputStream() {
            private val buffer = StringBuilder()

            override fun write(b: Int) {
                if (b == '\n'.code) {
                    log(LogLevel.Error, buffer.toString())
                    buffer.setLength(0)
                } else {
                    buffer.append(b.toChar())
                }
            }
        })

        System.setErr(loggerStream)
    }
}

fun setupLogger(client: LanguageClient) {
    logger = LSPLogger(client)

    // This is to log the exceptions to log.txt file (JUL = java.util.log)
    Logger.getLogger("").addHandler(JULRedirector())

    logger.redirectSystemErr()
}

fun <T> profile(tag: String, message: String, fn: () -> T): T {
    trace("$tag $message")

    if(profileEnabled) {
        var result: T
        val time = measureTime {
            result = fn()
        }
        if(!profileInfo.containsKey(tag)) {
            profileInfo[tag] = Pair(1, time)
        } else {
            val value = profileInfo[tag]!!
            profileInfo[tag] = Pair(value.first + 1, value.second.plus(time))
        }
        return result
    } else {
        return fn()
    }
}

fun profileJvmStartup() {
    val runtimeMXBean = ManagementFactory.getRuntimeMXBean()
    val jvmStartTimeMillis = runtimeMXBean.startTime
    val deltaMillis = System.currentTimeMillis() - jvmStartTimeMillis
    profileInfo["JVM Startup"] = Pair(1, deltaMillis.milliseconds)
}

fun logProfileInfo() {
    if (!profileEnabled) return

    logger.log(LogLevel.Debug,  "------------")
    logger.log(LogLevel.Debug,  "PROFILE INFO")
    var totalDuration = Duration.ZERO
    profileInfo.entries.sortedByDescending { it.value.second }.forEach {
        val header = "${it.key} (x${it.value.first}):".padEnd(65)
        val formattedDuration = formatDuration(it.value.second)
        totalDuration += it.value.second
        logger.log(LogLevel.Debug,  "$header $formattedDuration")
    }
    logger.log(LogLevel.Debug,  "------------")
    logger.log(LogLevel.Debug,  "TOTAL: ${formatDuration(totalDuration)}")
    logger.log(LogLevel.Debug,  "------------")
    profileInfo.clear()
}

private fun formatDuration(duration: Duration): String {
    return "%.3f ms".format(duration.inWholeMicroseconds.toDouble() / 1000)
}

fun debug(message: String) {
    logger.log(LogLevel.Debug, "[DEBUG]: $message")
}

fun info(message: String) {
    logger.log(LogLevel.Info, "[INFO]: $message")
}

fun error(message: String) {
    logger.log(LogLevel.Error, "[ERROR]: $message")
}

fun trace(message: String) {
    logger.log(LogLevel.Trace, "[TRACE]: $message")
}

fun warn(message: String) {
    logger.log(LogLevel.Warning, "[WARN]: $message")
}

fun printModule(rootModule: Module, level: Int = 0) {
    val indent = "\t".repeat(level)
    when (rootModule) {
        is SourceModule -> {
            info("$indent- ${rootModule.id}")
        }

        is LibraryModule -> {
            info("$indent- ${rootModule.id}")
            info("$indent* ${rootModule.roots.first().absolutePathString().substringAfterLast("/")}")
        }

        else -> {
            throw Exception("Invalid KaModule!")
        }
    }
    rootModule.dependencies.forEach {
        printModule(it, level + 1)
    }
}

private class JULRedirector: Handler() {
    override fun publish(record: LogRecord) {
        when (record.level) {
            Level.SEVERE -> error(record.message)
            Level.WARNING -> warn(record.message)
            Level.INFO -> info(record.message)
            Level.CONFIG -> debug(record.message)
            Level.FINE -> trace(record.message)
            else -> trace(record.message)
        }

        val stackTrace = StringWriter().also { PrintWriter(it).use { pw -> record.thrown?.printStackTrace(pw) } }.toString()
        error(stackTrace)
    }

    override fun flush() {}
    override fun close() {}
}

fun printPsiTree(ktFile: KtFile) {
    val rootNode = ktFile.node.psi

    printPsiNode(rootNode, 0)
}

fun printPsiNode(node: PsiElement, depth: Int = 0) {
    val indent = "  ".repeat(depth)
    debug("$indent${node.javaClass.simpleName}: ${node.text}")

    for (child in node.children) {
        printPsiNode(child, depth + 1)
    }
}