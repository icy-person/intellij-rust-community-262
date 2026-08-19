inline fun testAssert(action: () -> Boolean, lazyMessage: () -> Any) {
    if (isUnitTestMode && !action()) throw AssertionError(lazyMessage())
}

fun <T> runWithCheckCanceled(callable: () -> T): T =
    ApplicationUtil.runWithCheckCanceled(callable, ProgressManager.getInstance().progressIndicator)

fun <T> Project.computeWithCancelableProgress(@ProgressTitle title: String, supplier: () -> T): T =
    if (isUnitTestMode) supplier() else ProgressManager.getInstance().runProcessWithProgressSynchronously(supplier, title, true, this)

fun Project.runWithCancelableProgress(@ProgressTitle title: String, process: () -> Unit): Boolean =
    if (isUnitTestMode) { process(); true } else ProgressManager.getInstance().runProcessWithProgressSynchronously(process, title, true, this)

inline fun <T : Any> UserDataHolder.getOrPut(key: Key<T>, defaultValue: () -> T): T {
    val data = getUserData(key)
    if (data != null) return data
    val value = defaultValue()
    putUserData(key, value)
    return value
}

const val PLUGIN_ID = "org.rust.lang"
fun plugin(): IdeaPluginDescriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))!!
val String.escaped: String get() = StringUtil.escapeXmlEntities(this)

fun <T> runReadActionInSmartMode(dumbService: DumbService, action: () -> T): T {
    ProgressManager.checkCanceled()
    if (dumbService.project.isDisposed) throw ProcessCanceledException()
    return dumbService.runReadActionInSmartMode(Computable { ProgressManager.checkCanceled(); action() })
}

fun <T : Any> executeUnderProgressWithWriteActionPriorityWithRetries(indicator: ProgressIndicator, action: (ProgressIndicator) -> T): T {
    indicator.checkCanceled()
    if (isUnitTestMode && ApplicationManager.getApplication().isReadAccessAllowed) return action(indicator)
    checkReadAccessNotAllowed()
    var result: T? = null
    do {
        val wrappedIndicator = SensitiveProgressWrapper(indicator)
        val success = runWithWriteActionPriority(wrappedIndicator) { result = action(wrappedIndicator) }
        if (!success) {
            indicator.checkCanceled()
            ApplicationManager.getApplication().runReadAction(EmptyRunnable.getInstance())
        }
    } while (!success)
    return result!!
}

fun runWithWriteActionPriority(indicator: ProgressIndicator, action: () -> Unit): Boolean =
    ProgressIndicatorUtils.runWithWriteActionPriority(action, indicator)
fun runInReadActionWithWriteActionPriority(indicator: ProgressIndicator, action: () -> Unit): Boolean =
    ProgressIndicatorUtils.runInReadActionWithWriteActionPriority(action, indicator)

fun <T : Any> computeInReadActionWithWriteActionPriority(indicator: ProgressIndicator, action: () -> T): T {
    lateinit var result: T
    val success = runInReadActionWithWriteActionPriority(indicator) { result = action() }
    if (!success) throw ProcessCanceledException()
    return result
}

fun <T> executeUnderProgress(indicator: ProgressIndicator, action: () -> T): T {
    var result: T? = null
    ProgressManager.getInstance().executeProcessUnderProgress({ result = action() }, indicator)
    @Suppress("UNCHECKED_CAST") return result ?: (null as T)
}

fun ProgressIndicator.toThreadSafeProgressIndicator(): ProgressIndicator = if (this is ProgressIndicatorEx) {
    val threadSafeIndicator = EmptyProgressIndicator()
    addStateDelegate(object : AbstractProgressIndicatorExBase() { override fun cancel() = threadSafeIndicator.cancel() })
    threadSafeIndicator
} else this

fun <T : PsiElement> T.createSmartPointer(): SmartPsiElementPointer<T> =
    SmartPointerManager.getInstance(project).createSmartPsiElementPointer(this)

val DataContext.psiFile: PsiFile? get() = getData(CommonDataKeys.PSI_FILE)
val DataContext.editor: Editor? get() = getData(CommonDataKeys.EDITOR)
val DataContext.project: Project? get() = getData(CommonDataKeys.PROJECT)
val DataContext.elementUnderCaretInEditor: PsiElement?
    get() = psiFile?.let { file -> editor?.let { file.findElementAt(it.caretModel.offset) } }

fun isFeatureEnabled(featureId: String): Boolean {
    if (isHeadlessEnvironment) {
        val value = System.getProperty(featureId)?.toBooleanStrictOrNull()
        if (value != null) return value
    }
    return Experiments.getInstance().isFeatureEnabled(featureId)
}

fun setFeatureEnabled(featureId: String, enabled: Boolean) = Experiments.getInstance().setFeatureEnabled(featureId, enabled)

fun <T> runWithEnabledFeatures(vararg featureIds: String, action: () -> T): T {
    val currentValues = featureIds.map { it to isFeatureEnabled(it) }
    featureIds.forEach { setFeatureEnabled(it, true) }
    return try { action() } finally { currentValues.forEach { (id, value) -> setFeatureEnabled(id, value) } }
}

fun <T, D> getCachedOrCompute(
    dataHolder: UserDataHolder,
    key: Key<SoftReference<Pair<T, D>>>,
    dependency: D,
    provider: () -> T
): T {
    val oldResult = dataHolder.getUserData(key)?.get()
    if (oldResult != null && oldResult.second == dependency) return oldResult.first
    val value = provider()
    dataHolder.putUserData(key, SoftReference(value to dependency))
    return value
}

inline fun <R> Project.nonBlocking(crossinline block: () -> R, crossinline uiContinuation: (R) -> Unit) {
    if (isUnitTestMode) {
        uiContinuation(block())
    } else {
        AppExecutorUtil.getAppExecutorService().execute {
            val result = ApplicationManager.getApplication().runReadAction(Computable<R> { block() })
            ApplicationManager.getApplication().invokeLater({ uiContinuation(result) }, ModalityState.current())
        }
    }
}

@Service
class RsPluginDisposable : Disposable {
    companion object {
        @JvmStatic fun getInstance(project: Project): Disposable = project.service<RsPluginDisposable>()
    }
    override fun dispose() = Unit
}

inline fun <reified T : Configurable> Project.showSettingsDialog() {
    ShowSettingsUtil.getInstance().showSettingsDialog(this, T::class.java)
}
