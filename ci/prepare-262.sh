#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

p = Path("build.gradle.kts")
s = p.read_text()

replacements = {
    'val tomlPlugin = "org.toml.lang:262.9437.22"': 'val tomlPlugin: String by project',
    'id("org.jetbrains.intellij.platform") version "2.13.1"': 'id("org.jetbrains.intellij.platform") version "2.18.1"',
    'id("org.jetbrains.grammarkit") version "2023.3.0.2"': 'id("org.jetbrains.intellij.platform.grammarkit") version "2.18.1"',
    'kotlin("jvm") version "2.2.20"': 'kotlin("jvm") version "2.3.20"',
    'environment("IDEA_BUILD_NUMBER", "253")': 'environment("IDEA_BUILD_NUMBER", "262")',
    '            bundledPlugins(listOf(tomlPlugin))': '            plugins(listOf(tomlPlugin))',
    'plugin("org.jetbrains.grammarkit")': 'plugin("org.jetbrains.intellij.platform.grammarkit")',
    'import org.gradle.api.JavaVersion.VERSION_21': 'import org.gradle.api.JavaVersion.VERSION_25',
    'sourceCompatibility = VERSION_21': 'sourceCompatibility = VERSION_25',
    'targetCompatibility = VERSION_21': 'targetCompatibility = VERSION_25',
    'jvmTarget.set(JvmTarget.JVM_21)': 'jvmTarget.set(JvmTarget.JVM_25)',
    'jvmTarget.set(JvmTarget.JVM_24)': 'jvmTarget.set(JvmTarget.JVM_25)',
    'jvmTarget.set(JvmTarget.fromTarget("25"))': 'jvmTarget.set(JvmTarget.JVM_25)',
}

for old, new in replacements.items():
    s = s.replace(old, new)

needle = '            create(baseIDE, baseVersion) { useCache = true }'
if needle in s and 'if (baseIDE == "IC")' not in s:
    s = s.replace(
        needle,
        '''            if (baseIDE == "IC") {
                intellijIdea(ideaVersion) { useCache = true }
            } else {
                create(baseIDE, baseVersion) { useCache = true }
            }''',
        1,
    )

# ML ranking is no longer a bundled plugin in IDEA 2026.2.
s = s.replace('val mlCompletionPlugin = "com.intellij.completion.ml.ranking"\n', '')
s = s.replace('                javaScriptPlugin,\n                mlCompletionPlugin\n', '                javaScriptPlugin\n')
s = s.replace('            bundledPlugins(listOf(mlCompletionPlugin))\n', '')

# IntelliJ 2026.2 modularized several APIs that this older Rust plugin uses.
marker = '            bundledModule("intellij.spellchecker")\n'
modules = '''            bundledModule("intellij.spellchecker")
            bundledModule("intellij.platform.smRunner")
            bundledModule("intellij.platform.structureView.impl")
            bundledModule("intellij.platform.testRunner")
            bundledModule("intellij.platform.structuralSearch")
            bundledModule("intellij.platform.structureView")
            bundledModule("intellij.platform.navbar")
'''
if marker in s and 'bundledModule("intellij.platform.smRunner")' not in s:
    s = s.replace(marker, modules, 1)

p.write_text(s)
PY

# IntelliJ 2026.2 API migrations needed by this codebase.
python3 - <<'PY'
from pathlib import Path

p = Path("src/main/kotlin/org/rust/cargo/runconfig/buildtool/CargoBuildAdapter.kt")
s = p.read_text()
s = s.replace('import com.intellij.execution.runners.ExecutionUtil\n', '')
s = s.replace('import com.intellij.execution.ExecutionManager\n', '')
s = s.replace('ExecutionUtil.restart(environment)', 'ExecutionManagerImpl.getInstance(environment.project).restartRunProfile(null, environment, null)')
if 'import com.intellij.execution.impl.ExecutionManagerImpl\n' not in s:
    s = s.replace('import com.intellij.execution.impl.ExecutionManagerImpl\n', 'import com.intellij.execution.impl.ExecutionManagerImpl\n') if 'import com.intellij.execution.impl.ExecutionManagerImpl\n' in s else s.replace('import com.intellij.execution.impl.ExecutionManagerImpl\n', '')
# Add required implementation import near execution imports.
if 'import com.intellij.execution.impl.ExecutionManagerImpl\n' not in s:
    s = s.replace('import com.intellij.execution.impl.ExecutionManagerImpl\n', 'import com.intellij.execution.impl.ExecutionManagerImpl\n')
if 'import com.intellij.execution.impl.ExecutionManagerImpl\n' not in s:
    s = s.replace('import com.intellij.execution.ExecutorRegistry\n', 'import com.intellij.execution.ExecutorRegistry\nimport com.intellij.execution.impl.ExecutionManagerImpl\n')
p.write_text(s)

p = Path("src/main/kotlin/org/rust/lang/core/resolve2/util/RsBlockStubBuilder.kt")
s = p.read_text()
s = s.replace(
'''        override fun createStub(parentStub: StubElement<*>, node: ASTNode): StubElement<*>? {
            val nodeType = node.elementType
            val factory = StubElementRegistryService.getInstance().getStubFactory(nodeType) ?: return null
''',
'''        override fun createStub(
            parentStub: StubElement<*>,
            node: ASTNode,
            registryService: StubElementRegistryService
        ): StubElement<*>? {
            val nodeType = node.elementType
            val factory = registryService.getStubFactory(nodeType) ?: return null
''')
p.write_text(s)

p = Path("src/main/kotlin/org/rust/openapiext/utils.kt")
s = p.read_text()
s = s.replace('import com.intellij.ide.ui.LafManager\n', '')
s = s.replace('import com.intellij.ide.ui.laf.UIThemeBasedLookAndFeelInfo\n', '')
s = s.replace(
'''val isUnderDarkTheme: Boolean
    get() {
        val lookAndFeel = LafManager.getInstance().currentLookAndFeel as? UIThemeBasedLookAndFeelInfo
        return lookAndFeel?.theme?.isDark == true || UIUtil.isUnderDarcula()
    }
''',
'''val isUnderDarkTheme: Boolean
    get() = UIUtil.isUnderDarcula()
''')
p.write_text(s)
PY

grep -nE 'smRunner|structureView.impl|testRunner|structuralSearch|restartRunProfile|createStub\(|isUnderDarkTheme' build.gradle.kts src/main/kotlin/org/rust/cargo/runconfig/buildtool/CargoBuildAdapter.kt src/main/kotlin/org/rust/lang/core/resolve2/util/RsBlockStubBuilder.kt src/main/kotlin/org/rust/openapiext/utils.kt || true
