package org.kotlinlsp.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import org.kotlinlsp.analysis.AnalysisSession
import org.kotlinlsp.info
import org.kotlinlsp.setupLogger
import org.kotlinlsp.trace
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import kotlin.system.exitProcess

class MyLanguageServer: LanguageServer, TextDocumentService, WorkspaceService, LanguageClientAware {
    private lateinit var client: LanguageClient
    private lateinit var analysisSession: AnalysisSession
    private lateinit var rootPath: String

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities().apply {
            textDocumentSync = Either.forLeft(TextDocumentSyncKind.Incremental)
        }

        rootPath = params.workspaceFolders.first().uri.removePrefix("file://")

        return completedFuture(InitializeResult(capabilities))
    }

    override fun initialized(params: InitializedParams) {
        setupLogger(rootPath)
        info(rootPath)

        analysisSession = AnalysisSession(
            rootPath = rootPath,
            onDiagnostics = {
                client.publishDiagnostics(it)
            }
        )
    }

    override fun shutdown(): CompletableFuture<Any> {
        exit()  // TODO Nvim does not call exit so the server is kept alive and reparented to the init process (?)
        return completedFuture(null)
    }

    override fun exit() {
        exitProcess(0)
    }

    override fun getTextDocumentService(): TextDocumentService = this
    override fun getWorkspaceService(): WorkspaceService = this

    override fun didOpen(params: DidOpenTextDocumentParams) {
        analysisSession.onOpenFile(params.textDocument.uri)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        analysisSession.onChangeFile(params.textDocument.uri, params.textDocument.version, params.contentChanges)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        analysisSession.onCloseFile(params.textDocument.uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {

    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {

    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {

    }

    override fun connect(params: LanguageClient) {
        client = params
    }
}
