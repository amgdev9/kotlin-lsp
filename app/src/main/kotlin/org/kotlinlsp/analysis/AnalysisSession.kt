package org.kotlinlsp.analysis

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.mock.MockApplication
import com.intellij.mock.MockComponentManager
import com.intellij.mock.MockProject
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.impl.DocumentWriteAccessGuard
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.file.impl.JavaFileManager
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.impl.VirtualFileEnumeration
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.messages.Topic
import org.eclipse.lsp4j.*
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.platform.KotlinPlatformSettings
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.java.KotlinJavaModuleAccessibilityChecker
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinReadActionConfinementLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.permissions.KotlinAnalysisPermissionOptions
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinGlobalSearchScopeMerger
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinSimpleGlobalSearchScopeMerger
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionProvider
import org.jetbrains.kotlin.analysis.decompiler.psi.BuiltinsVirtualFileProvider
import org.jetbrains.kotlin.analysis.decompiler.psi.BuiltinsVirtualFileProviderCliImpl
import org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationListener
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionConfigurator
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationTopics
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndexImpl
import org.jetbrains.kotlin.cli.jvm.index.SingleJavaFileRootsIndex
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.load.kotlin.JvmType
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.analysis.services.*
import org.kotlinlsp.analysis.services.modules.LibraryModule
import org.kotlinlsp.analysis.services.modules.SourceModule
import org.kotlinlsp.buildsystem.getModuleList
import org.kotlinlsp.trace
import kotlin.reflect.full.primaryConstructor

@OptIn(KaExperimentalApi::class)
class AnalysisSession(
    val rootPath: String,
    private val onDiagnostics: (params: PublishDiagnosticsParams) -> Unit
) {
    private val app: MockApplication
    private val project: MockProject
    private val commandProcessor: CommandProcessor
    private val psiDocumentManager: PsiDocumentManager
    private val openedFiles = mutableMapOf<String, KtFile>()

    init {
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

        registerFIRServices(project, app)

        app.apply {
            // TODO Intellij uses VFSUtil propietary class
            registerService(BuiltinsVirtualFileProvider::class.java, BuiltinsVirtualFileProviderCliImpl::class.java)

            registerService(KotlinAnalysisPermissionOptions::class.java, AnalysisPermissionOptions::class.java)
        }

        project.apply {
            registerService(KotlinProjectStructureProvider::class.java, ProjectStructureProvider::class.java)
            registerService(
                KotlinLifetimeTokenFactory::class.java,
                KotlinReadActionConfinementLifetimeTokenFactory::class.java
            )
            registerService(KotlinPlatformSettings::class.java, PlatformSettings::class.java)
            registerService(
                KotlinDeclarationProviderFactory::class.java,
                DeclarationProviderFactory::class.java
            )
            registerService(KotlinPackageProviderFactory::class.java, PackageProviderFactory::class.java)
            // TODO We can optimize this by providing our own, as IJ kotlin plugin does
            registerService(KotlinGlobalSearchScopeMerger::class.java, KotlinSimpleGlobalSearchScopeMerger::class.java)

            registerService(KotlinAnnotationsResolverFactory::class.java, AnnotationsResolverFactory::class.java)
            registerService(KotlinModuleDependentsProvider::class.java, ModuleDependentsProvider::class.java)
            registerService(KotlinPackagePartProviderFactory::class.java, PackagePartProviderFactory::class.java)
            registerService(KotlinJavaModuleAccessibilityChecker::class.java, JavaModuleAccessibilityChecker::class.java)
        }

        CoreApplicationEnvironment.registerExtensionPoint(
            project.extensionArea,
            KaResolveExtensionProvider.EP_NAME,
            KaResolveExtensionProvider::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            project.extensionArea,
            "org.jetbrains.kotlin.llFirSessionConfigurator",
            LLFirSessionConfigurator::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            project.extensionArea,
            "com.intellij.java.elementFinder",
            JavaElementFinder::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            app.extensionArea,
            DocumentWriteAccessGuard.EP_NAME,
            WriteAccessGuard::class.java
        )

        val pluginDescriptor = DefaultPluginDescriptor("analysis-api-lsp-base-loader-2")
        val kcsrClass = loadClass(
            app,
            "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaResolveExtensionToContentScopeRefinerBridge",
            pluginDescriptor
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            project.extensionArea,
            "org.jetbrains.kotlin.kotlinContentScopeRefiner",
            kcsrClass::class.java
        )

        // This setup comes from standalone platform
        val javaFileManager = project.getService(JavaFileManager::class.java) as KotlinCliJavaFileManagerImpl
        val packagePartsScope = ProjectScope.getLibrariesScope(project)
        val libraryRoots = mutableListOf<JavaRoot>()
        val rootModule = getModuleList(project, appEnvironment, rootPath)
        fetchLibraryRoots(rootModule, libraryRoots)

        val packagePartProvider = JvmPackagePartProvider(latestLanguageVersionSettings, packagePartsScope).apply {
            addRoots(libraryRoots, MessageCollector.NONE)
        }
        val rootsIndex = JvmDependenciesIndexImpl(libraryRoots, shouldOnlyFindFirstClass = false)   // TODO Should receive all (sources + libraries + jdk)
        javaFileManager.initialize(
            index = rootsIndex,
            packagePartProviders = listOf(packagePartProvider),
            singleJavaFileRootsIndex = SingleJavaFileRootsIndex(emptyList()),
            usePsiClassFilesReading = true,
            perfManager = null,
        )
        val fileFinderFactory = CliVirtualFileFinderFactory(rootsIndex, false, perfManager = null)
        project.registerService(VirtualFileFinderFactory::class.java, fileFinderFactory)

        (project.getService(KotlinProjectStructureProvider::class.java) as ProjectStructureProvider).setup(project, appEnvironment, rootPath)
        (project.getService(KotlinPackageProviderFactory::class.java) as PackageProviderFactory).setup(project)
        (project.getService(KotlinDeclarationProviderFactory::class.java) as DeclarationProviderFactory).setup(project)

        commandProcessor = app.getService(CommandProcessor::class.java)
        psiDocumentManager = PsiDocumentManager.getInstance(project)
    }

    @OptIn(KaImplementationDetail::class)
    private fun registerFIRServices(project: MockProject, app: MockApplication) {
        val pluginDescriptor = DefaultPluginDescriptor("analysis-api-lsp-base-loader")

        // These services come from analysis-api-fhir.xml, so we need to keep synced with this file in case of analysis API libraries updates
        registerFIRService(
            project,
            "org.jetbrains.kotlin.analysis.api.session.KaSessionProvider",
            "org.jetbrains.kotlin.analysis.api.fir.KaFirSessionProvider",
            pluginDescriptor
        )
        registerFIRService(project, "org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService",
            "org.jetbrains.kotlin.analysis.api.fir.modification.KaFirSourceModificationService", pluginDescriptor
        )
        registerFIRService(project, "org.jetbrains.kotlin.idea.references.KotlinReferenceProviderContributor",
            "org.jetbrains.kotlin.analysis.api.fir.references.KotlinFirReferenceContributor", pluginDescriptor
        )
        registerFIRService(project, "org.jetbrains.kotlin.idea.references.ReadWriteAccessChecker",
            "org.jetbrains.kotlin.analysis.api.fir.references.ReadWriteAccessCheckerFirImpl", pluginDescriptor
        )
        registerFIRService(project, "org.jetbrains.kotlin.analysis.api.imports.KaDefaultImportsProvider"
            ,"org.jetbrains.kotlin.analysis.api.fir.KaFirDefaultImportsProvider", pluginDescriptor
        )
        registerFIRService(project, "org.jetbrains.kotlin.analysis.api.platform.statistics.KaStatisticsService"
            ,"org.jetbrains.kotlin.analysis.api.fir.statistics.KaFirStatisticsService", pluginDescriptor
        )
        registerProjectListener(project, "org.jetbrains.kotlin.analysis.api.fir.KaFirSessionProvider\$SessionInvalidationListener", LLFirSessionInvalidationTopics.SESSION_INVALIDATION, pluginDescriptor)

        // LL FIR services, these come from low-level-api-fhir.xml
        registerFIRServiceClass(project, "org.jetbrains.kotlin.analysis.low.level.api.fir.statistics.LLStatisticsService", pluginDescriptor)
        registerFIRServiceClass(project, "org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationTracker", pluginDescriptor)
        registerFIRServiceClass(project, "org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirDeclarationModificationService", pluginDescriptor)
        registerFIRServiceClass(project, "org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationEventPublisher", pluginDescriptor)
        registerFIRServiceClass(project, "org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationService", pluginDescriptor)
        registerFIRService(project, "org.jetbrains.kotlin.analysis.low.level.api.fir.api.services.LLFirElementByPsiElementChooser", "org.jetbrains.kotlin.analysis.low.level.api.fir.services.LLRealFirElementByPsiElementChooser", pluginDescriptor)
        registerFIRServiceClass(
            project,
            "org.jetbrains.kotlin.analysis.low.level.api.fir.projectStructure.LLFirBuiltinsSessionFactory",
            pluginDescriptor
        )
        registerFIRServiceClass(
            project,
            "org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirResolveSessionService",
            pluginDescriptor
        )
        registerFIRServiceClass(
            project,
            "org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionCache",
            pluginDescriptor
        )
        registerFIRServiceClass(project, "org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirGlobalResolveComponents", pluginDescriptor)
        registerFIRService(app, "org.jetbrains.kotlin.analysis.api.platform.resolution.KaResolutionActivityTracker", "org.jetbrains.kotlin.analysis.low.level.api.fir.lazy.resolve.LLFirResolutionActivityTracker", pluginDescriptor)
        registerProjectListener(project, "org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationService\$LLKotlinModificationEventListener", KotlinModificationEvent.TOPIC, pluginDescriptor)
        registerProjectListener(project, "org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationService\$LLPsiModificationTrackerListener", PsiModificationTracker.TOPIC, pluginDescriptor)
        val theTopic = Topic(
            LLFirInBlockModificationListener::class.java,
            Topic.BroadcastDirection.TO_CHILDREN,
            true,
        )
        registerProjectListener(project, "org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationListenerForCodeFragments", theTopic, pluginDescriptor)
        registerProjectListener(project, "org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationTracker\$Listener", theTopic, pluginDescriptor)

        // analysis-api-impl-base.xml
        registerFIRServiceClass(app, "org.jetbrains.kotlin.analysis.decompiled.light.classes.origin.KotlinDeclarationInCompiledFileSearcher", pluginDescriptor)
        registerFIRService(project, "org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaContentScopeProvider", "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseContentScopeProvider", pluginDescriptor)
        registerFIRService(project, "org.jetbrains.kotlin.analysis.api.platform.KotlinMessageBusProvider", "org.jetbrains.kotlin.analysis.api.platform.KotlinProjectMessageBusProvider", pluginDescriptor)
        registerFIRService(project, "org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider", "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseModuleProvider", pluginDescriptor)
        registerFIRService(project, "org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade", "org.jetbrains.kotlin.analysis.api.impl.base.java.KaBaseKotlinJavaPsiFacade", pluginDescriptor)
        registerFIRService(project, "org.jetbrains.kotlin.load.java.structure.impl.source.JavaElementSourceFactory", "org.jetbrains.kotlin.analysis.api.impl.base.java.source.JavaElementSourceWithSmartPointerFactory", pluginDescriptor)
        registerFIRService(project, "org.jetbrains.kotlin.psi.KotlinReferenceProvidersService", "org.jetbrains.kotlin.analysis.api.impl.base.references.HLApiReferenceProviderService", pluginDescriptor)
        registerFIRService(
            app, "org.jetbrains.kotlin.analysis.api.permissions.KaAnalysisPermissionRegistry",
            "org.jetbrains.kotlin.analysis.api.impl.base.permissions.KaBaseAnalysisPermissionRegistry", pluginDescriptor
        )
        registerFIRService(
            project, "org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleResolver",
            "org.jetbrains.kotlin.analysis.api.impl.base.java.KaBaseJavaModuleResolver", pluginDescriptor
        )
        registerFIRService(project, "org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScopeProvider",
            "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseResolutionScopeProvider", pluginDescriptor)
        registerFIRService(project, "org.jetbrains.kotlin.analysis.api.platform.permissions.KaAnalysisPermissionChecker", "org.jetbrains.kotlin.analysis.api.impl.base.permissions.KaBaseAnalysisPermissionChecker", pluginDescriptor)
        registerFIRService(project, "org.jetbrains.kotlin.analysis.api.platform.lifetime.KaLifetimeTracker", "org.jetbrains.kotlin.analysis.api.impl.base.lifetime.KaBaseLifetimeTracker", pluginDescriptor)
    }

    @OptIn(KaPlatformInterface::class)
    private fun fetchLibraryRoots(module: KaModule, roots: MutableList<JavaRoot>, cache: MutableMap<String, Boolean> = mutableMapOf()) {
        when(module) {
            is SourceModule -> {
                module.directRegularDependencies.forEach {
                    fetchLibraryRoots(it, roots, cache)
                }
            }
            is LibraryModule -> {
                if(cache.get(module.libraryName) == null) {
                    VirtualFileEnumeration.extract(module.baseContentScope)?.filesIfCollection?.forEach {
                        val root = JavaRoot(it, JavaRoot.RootType.BINARY)
                        roots.add(root)
                    }
                    module.directRegularDependencies.forEach {
                        fetchLibraryRoots(it, roots, cache)
                    }
                    cache.set(module.libraryName, true)
                }
            }
            else -> throw Exception("Unsupported KaModule! $module")
        }
    }

    private fun registerFIRService(componentManager: MockComponentManager, interfaceName: String, implClassName: String, pluginDescriptor: DefaultPluginDescriptor) {
        val iface = componentManager.loadClass<JvmType.Object>(interfaceName, pluginDescriptor)
        val implClass = componentManager.loadClass<JvmType.Object>(implClassName, pluginDescriptor)
        componentManager.registerService(iface, implClass)
    }

    private fun registerFIRServiceClass(componentManager: MockComponentManager, implClassName: String, pluginDescriptor: DefaultPluginDescriptor) {
        val implClass = componentManager.loadClass<JvmType.Object>(implClassName, pluginDescriptor)
        componentManager.registerService(implClass)
    }

    private fun loadClass(componentManager: MockComponentManager, className: String, pluginDescriptor: DefaultPluginDescriptor): Class<JvmType.Object> {
        return componentManager.loadClass(className, pluginDescriptor)
    }

    private fun <T: Any> registerProjectListener(project: MockProject, listenerClass: String, topic: Topic<T>, pluginDescriptor: DefaultPluginDescriptor) {
        val sessionInvalidationListener = loadClass(project, listenerClass, pluginDescriptor) as Class<T>
        project.messageBus.connect().subscribe<T>(
            topic,
            sessionInvalidationListener.kotlin.primaryConstructor!!.call(project)
        )
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
        KaSourceModificationService.getInstance(project)
            .handleElementModification(ktFile, KaElementModificationType.Unknown)

        updateDiagnostics(ktFile)
    }
}

private fun KaSeverity.toLspSeverity(): DiagnosticSeverity =
    when(this) {
        KaSeverity.ERROR -> DiagnosticSeverity.Error
        KaSeverity.WARNING -> DiagnosticSeverity.Warning
        KaSeverity.INFO -> DiagnosticSeverity.Information
    }

private fun Position.toOffset(ktFile: KtFile): Int = StringUtil.lineColToOffset(ktFile.text, line, character)

private fun TextRange.toLspRange(ktFile: KtFile): Range {
    val text = ktFile.text
    val lineColumnStart = StringUtil.offsetToLineColumn(text, startOffset)
    val lineColumnEnd = StringUtil.offsetToLineColumn(text, endOffset)

    return Range(
        Position(lineColumnStart.line, lineColumnStart.column),
        Position(lineColumnEnd.line, lineColumnEnd.column)
    )
}
