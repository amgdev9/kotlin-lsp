package org.kotlinlsp.analysis

import com.intellij.core.CorePackageIndex
import com.intellij.mock.MockApplication
import com.intellij.mock.MockProject
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.roots.PackageIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.file.impl.JavaFileManager
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j.*
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.impl.base.util.LibraryUtils
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesDynamicCompoundIndex
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndexImpl
import org.jetbrains.kotlin.cli.jvm.index.SingleJavaFileRootsIndex
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleFinder
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleResolver
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.cli.jvm.modules.JavaModuleGraph
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.actions.goToDefinitionAction
import org.kotlinlsp.actions.hoverAction
import org.kotlinlsp.analysis.registration.Registrar
import org.kotlinlsp.analysis.registration.lspPlatform
import org.kotlinlsp.analysis.registration.lspPlatformPostInit
import org.kotlinlsp.analysis.services.*
import org.kotlinlsp.analysis.services.modules.LibraryModule
import org.kotlinlsp.analysis.services.modules.SourceModule
import org.kotlinlsp.buildsystem.BuildSystemResolver
import org.kotlinlsp.utils.toLspRange
import org.kotlinlsp.utils.toOffset
import kotlin.io.path.absolutePathString

class AnalysisSession(private val onDiagnostics: (params: PublishDiagnosticsParams) -> Unit) {
    private val app: MockApplication
    private val project: MockProject
    private val commandProcessor: CommandProcessor
    private val psiDocumentManager: PsiDocumentManager
    private val buildSystemResolver: BuildSystemResolver
    private val openedFiles = mutableMapOf<String, KtFile>()

    init {
        System.setProperty("java.awt.headless", "true")
        setupIdeaStandaloneExecution()

        val projectDisposable = Disposer.newDisposable("LSPAnalysisAPISession.project")
        val compilerConfiguration = CompilerConfiguration()
        val appEnvironment = KotlinCoreEnvironment.getOrCreateApplicationEnvironment(
            projectDisposable,
            compilerConfiguration,
            KotlinCoreApplicationEnvironmentMode.Production
        )
        val coreEnvironment = KotlinCoreProjectEnvironment(projectDisposable, appEnvironment)
        project = coreEnvironment.project
        app = appEnvironment.application

        val registrar = Registrar(project, app, projectDisposable)
        registrar.lspPlatform()

        // Call the appropriate build system to get the modules to analyze
        buildSystemResolver = BuildSystemResolver(project, appEnvironment)
        val rootModule = buildSystemResolver.resolveModuleDAG()

        project.setupHighestLanguageLevel()
        val librariesScope = ProjectScope.getLibrariesScope(project)
        val libraryRoots = mutableListOf<JavaRoot>()
        fetchLibraryRoots(rootModule, libraryRoots)

        val javaFileManager = project.getService(JavaFileManager::class.java) as KotlinCliJavaFileManagerImpl
        val javaModuleFinder = CliJavaModuleFinder(null, null, javaFileManager, project, null)
        val javaModuleGraph = JavaModuleGraph(javaModuleFinder)
        val delegateJavaModuleResolver = CliJavaModuleResolver(
            javaModuleGraph,
            emptyList(),
            emptyList(),    // This is always empty in standalone platform
            project,
        )

        val corePackageIndex = project.getService(PackageIndex::class.java) as CorePackageIndex

        val packagePartProvider = JvmPackagePartProvider(latestLanguageVersionSettings, librariesScope).apply {
            addRoots(libraryRoots, MessageCollector.NONE)
        }
        val rootsIndex = JvmDependenciesDynamicCompoundIndex(shouldOnlyFindFirstClass = false).apply {
            addIndex(
                JvmDependenciesIndexImpl(
                    libraryRoots,
                    shouldOnlyFindFirstClass = false
                )
            )  // TODO Should receive all (sources + libraries)
            indexedRoots.forEach { javaRoot ->
                if (javaRoot.file.isDirectory) {
                    if (javaRoot.type == JavaRoot.RootType.SOURCE) {
                        javaFileManager.addToClasspath(javaRoot.file)
                        corePackageIndex.addToClasspath(javaRoot.file)
                    } else {
                        coreEnvironment.addSourcesToClasspath(javaRoot.file)
                    }
                }
            }
        }

        javaFileManager.initialize(
            index = rootsIndex,
            packagePartProviders = listOf(packagePartProvider),
            singleJavaFileRootsIndex = SingleJavaFileRootsIndex(emptyList()),
            usePsiClassFilesReading = true,
            perfManager = null,
        )
        val fileFinderFactory = CliVirtualFileFinderFactory(rootsIndex, false, perfManager = null)

        registrar.lspPlatformPostInit(
            cliJavaModuleResolver = delegateJavaModuleResolver,
            cliVirtualFileFinderFactory = fileFinderFactory
        )

        // Setup platform services
        (project.getService(KotlinProjectStructureProvider::class.java) as ProjectStructureProvider).setup(
            rootModule
        )
        (project.getService(KotlinPackageProviderFactory::class.java) as PackageProviderFactory).setup(project)
        (project.getService(KotlinDeclarationProviderFactory::class.java) as DeclarationProviderFactory).setup(project)
        // Here libraryRoots should have .class files, not .jar files
        (project.getService(KotlinPackagePartProviderFactory::class.java) as PackagePartProviderFactory).setup(
            libraryRoots
        )

        commandProcessor = app.getService(CommandProcessor::class.java)
        psiDocumentManager = PsiDocumentManager.getInstance(project)
    }

    @OptIn(KaPlatformInterface::class, KaImplementationDetail::class)
    private fun fetchLibraryRoots(module: KaModule, roots: MutableList<JavaRoot>, cache: MutableMap<String, Boolean> = mutableMapOf()) {
        when(module) {
            is SourceModule -> {
                module.directRegularDependencies.forEach {
                    fetchLibraryRoots(it, roots, cache)
                }
            }
            is LibraryModule -> {
                if(cache.get(module.libraryName) == null) {
                    if(module.isSdk) {
                        val jdkRoots = LibraryUtils.findClassesFromJdkHome(module.binaryRoots.first(), isJre = false).map {
                            val adjustedPath = adjustModulePath(it.absolutePathString())
                            val virtualFile = CoreJrtFileSystem().findFileByPath(adjustedPath)!!
                            return@map JavaRoot(virtualFile, JavaRoot.RootType.BINARY)
                        }
                        roots.addAll(jdkRoots)
                    } else {
                        module.binaryRoots.forEach {
                            val virtualFile = CoreJarFileSystem().findFileByPath("${it.absolutePathString()}!/")!!
                            val root = JavaRoot(virtualFile, JavaRoot.RootType.BINARY)
                            roots.add(root)
                        }
                        module.directRegularDependencies.forEach {
                            fetchLibraryRoots(it, roots, cache)
                        }
                    }
                    cache.set(module.libraryName, true)
                }
            }
            else -> throw Exception("Unsupported KaModule! $module")
        }
    }

    private fun adjustModulePath(pathString: String): String {
        return if (pathString.contains("!/")) {
            // URLs loaded from JDK point to module names in a JRT protocol format,
            // e.g., "jrt:///path/to/jdk/home!/java.base" (JRT protocol prefix + JDK home path + JAR separator + module name)
            // After protocol erasure, we will see "/path/to/jdk/home!/java.base" as a binary root.
            // CoreJrtFileSystem.CoreJrtHandler#findFile, which uses Path#resolve, finds a virtual file path to the file itself,
            // e.g., "/path/to/jdk/home!/modules/java.base". (JDK home path + JAR separator + actual file path)
            // To work with that JRT handler, a hacky workaround here is to add "modules" before the module name so that it can
            // find the actual file path.
            // See [LLFirJavaFacadeForBinaries#getBinaryPath] and [StandaloneProjectFactory#getBinaryPath] for a similar hack.
            val (libHomePath, pathInImage) = CoreJrtFileSystem.splitPath(pathString)
            "$libHomePath!/modules/$pathInImage"
        } else
            pathString
    }

    fun onOpenFile(path: String) {
        val ktFile = loadKtFile(path)
        openedFiles[path] = ktFile

        updateDiagnostics(ktFile)
    }

    fun onCloseFile(path: String) {
        openedFiles.remove(path)
    }

    private fun loadKtFile(path: String): KtFile {
        val virtualFile = VirtualFileManager.getInstance()
            .findFileByUrl(path)!!
        return PsiManager.getInstance(project).findFile(virtualFile)!! as KtFile
    }

    private fun updateDiagnostics(ktFile: KtFile) {
        val syntaxDiagnostics = PsiTreeUtil.collectElementsOfType(ktFile, PsiErrorElement::class.java).map {
            return@map Diagnostic(
                it.textRange.toLspRange(ktFile),
                it.errorDescription,
                DiagnosticSeverity.Error,
                "Kotlin LSP"
            )
        }
        val analysisDiagnostics = analyze(ktFile) {
            val diagnostics = ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)

            val lspDiagnostics = diagnostics.map {
                return@map Diagnostic(
                    it.textRanges.first().toLspRange(ktFile),
                    it.defaultMessage,
                    it.severity.toLspSeverity(),
                    "Kotlin LSP"
                )
            }

            return@analyze lspDiagnostics
        }
        onDiagnostics(PublishDiagnosticsParams("file://${ktFile.virtualFilePath}", syntaxDiagnostics + analysisDiagnostics))
    }

    // TODO Use version to avoid conflicts
    fun onChangeFile(path: String, version: Int, changes: List<TextDocumentContentChangeEvent>) {
        val ktFile = openedFiles[path]!!
        val doc = psiDocumentManager.getDocument(ktFile)!!

        changes.forEach {
            commandProcessor.executeCommand(project, {
                val startOffset = it.range.start.toOffset(ktFile)
                val endOffset = it.range.end.toOffset(ktFile)

                doc.replaceString(startOffset, endOffset, it.text)
                psiDocumentManager.commitDocument(doc)
                ktFile.onContentReload()
            }, "onChangeFile", null)
        }

        // TODO Optimize the KaElementModificationType
        // TODO Add a debounce so analysis is not triggered on every keystroke adding lag
        KaSourceModificationService.getInstance(project)
            .handleElementModification(ktFile, KaElementModificationType.Unknown)

        updateDiagnostics(ktFile)
    }

    fun hover(path: String, position: Position): Pair<String, Range>? {
        val ktFile = openedFiles[path]!!
        return hoverAction(ktFile, position)
    }

    fun goToDefinition(path: String, position: Position): Location? {
        val ktFile = openedFiles[path]!!
        return goToDefinitionAction(ktFile, position)
    }
}

private fun KaSeverity.toLspSeverity(): DiagnosticSeverity =
    when(this) {
        KaSeverity.ERROR -> DiagnosticSeverity.Error
        KaSeverity.WARNING -> DiagnosticSeverity.Warning
        KaSeverity.INFO -> DiagnosticSeverity.Information
    }
