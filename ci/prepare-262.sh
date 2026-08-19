#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

p = Path("build.gradle.kts")
s = p.read_text()

helper_block = '''
fun hasProp(name: String): Boolean = extra.has(name)

fun prop(name: String): String =
    extra.properties[name] as? String
        ?: error("Property `$name` is not defined in gradle.properties")

fun versionForIde(ideName: String): String = when (ideName) {
    "IU", "IC" -> ideaVersion
    else -> error("Unexpected IDE name: `$baseIDE`")
}

fun <T : ModuleDependency> T.excludeKotlinDeps() {
    exclude(module = "kotlin-reflect")
    exclude(module = "kotlin-runtime")
    exclude(module = "kotlin-stdlib")
    exclude(module = "kotlin-stdlib-common")
    exclude(module = "kotlin-stdlib-jdk8")
    exclude(module = "kotlinx-serialization-core")
}
'''

old_helper = '''
fun hasProp(name: String): Boolean = extra.has(name)

fun prop(name: String): String =
    extra.properties[name] as? String
        ?: error("Property `$name` is not defined in gradle.properties")

fun versionForIde(ideName: String): String = when (ideName) {
    "IU", "IC" -> ideaVersion
    else -> error("Unexpected IDE name: `$baseIDE`")
}
'''
s = s.replace(old_helper, "\n")
old_exclude = '''
fun <T : ModuleDependency> T.excludeKotlinDeps() {
    exclude(module = "kotlin-reflect")
    exclude(module = "kotlin-runtime")
    exclude(module = "kotlin-stdlib")
    exclude(module = "kotlin-stdlib-common")
    exclude(module = "kotlin-stdlib-jdk8")
    exclude(module = "kotlinx-serialization-core")
}
'''
s = s.replace(old_exclude, "\n")
anchor = 'val isTeamcity = System.getenv("TEAMCITY_VERSION") != null\n'
if helper_block.strip() not in s:
    s = s.replace(anchor, anchor + helper_block, 1)

replacements = {
    'val tomlPlugin = "org.toml.lang:262.9437.22"': 'val tomlPlugin: String by project',
    'id("org.jetbrains.intellij.platform") version "2.13.1"': 'id("org.jetbrains.intellij.platform") version "2.18.1"',
    'id("org.jetbrains.grammarkit") version "2023.3.0.2"': 'id("org.jetbrains.intellij.platform.grammarkit") version "2.18.1"',
    'plugin("org.jetbrains.grammarkit")': 'plugin("org.jetbrains.intellij.platform.grammarkit")',
    'kotlin("jvm") version "2.2.20"': 'kotlin("jvm") version "2.3.20"',
    'environment("IDEA_BUILD_NUMBER", "253")': 'environment("IDEA_BUILD_NUMBER", "262")',
    'import org.gradle.api.JavaVersion.VERSION_21': 'import org.gradle.api.JavaVersion.VERSION_25',
    'sourceCompatibility = VERSION_21': 'sourceCompatibility = VERSION_25',
    'targetCompatibility = VERSION_21': 'targetCompatibility = VERSION_25',
    'jvmTarget.set(JvmTarget.JVM_21)': 'jvmTarget.set(JvmTarget.JVM_25)',
    'jvmTarget.set(JvmTarget.JVM_24)': 'jvmTarget.set(JvmTarget.JVM_25)',
    'jvmTarget.set(JvmTarget.fromTarget("25"))': 'jvmTarget.set(JvmTarget.JVM_25)',
    '            bundledPlugins(listOf(tomlPlugin))': '            plugins(listOf(tomlPlugin))',
}
for old, new in replacements.items():
    s = s.replace(old, new)

needle = '            create(baseIDE, baseVersion) { useCache = true }'
if needle in s and 'intellijIdea(ideaVersion)' not in s:
    s = s.replace(needle, '''            if (baseIDE == "IC") {
                intellijIdea(ideaVersion)
            } else {
                create(baseIDE, baseVersion)
            }''', 1)
s = s.replace('intellijIdea(ideaVersion) { useCache = true }', 'intellijIdea(ideaVersion)')
s = s.replace('create(baseIDE, baseVersion) { useCache = true }', 'create(baseIDE, baseVersion)')

s = s.replace('val mlCompletionPlugin = "com.intellij.completion.ml.ranking"\n', '')
s = s.replace('                javaScriptPlugin,\n                mlCompletionPlugin\n', '                javaScriptPlugin\n')
s = s.replace('            bundledPlugins(listOf(mlCompletionPlugin))\n', '')

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

for name in ("duplicates", "ml-completion"):
    s = s.replace(f'    "{name}",\n', '')
    s = s.replace(f'            pluginComposedModule(implementation(project(":{name}")))\n', '')

exact_blocks = {
    'duplicates': '''project(":duplicates") {
    dependencies {
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

''',
    'ml-completion': '''project(":ml-completion") {
    dependencies {
        implementation("org.jetbrains.intellij.deps.completion:completion-ranking-rust:0.4.1")
        implementation(project(":"))
        testImplementation(project(":", "testOutput"))
    }
}

''',
}
for block in exact_blocks.values():
    s = s.replace(block, '')

p.write_text(s)

# Patch the actual Kotlin source. Previous versions mistakenly applied this
# replacement to build.gradle.kts instead of utils.kt.
source = Path("src/main/kotlin/org/rust/openapiext/utils.kt")
source_text = source.read_text()
old = 'ReadAction.nonBlocking(Callable { block() })'
new = 'ReadAction.nonBlocking<R>(Callable<R> { block() })'
if old in source_text:
    source_text = source_text.replace(old, new)
elif 'ReadAction.nonBlocking<R>(Callable<R> { block() })' not in source_text:
    raise SystemExit("Expected Project.nonBlocking source expression was not found")
source.write_text(source_text)

p = Path("settings.gradle.kts")
s = p.read_text().replace('    "duplicates",\n', '').replace('    "ml-completion"\n', '')
p.write_text(s)

p = Path("plugin/src/main/resources/META-INF/plugin.xml")
s = p.read_text().replace('        <module name="org.rust.duplicates"/>\n', '').replace('        <module name="org.rust.mlCompletion"/>\n', '')
p.write_text(s)

leftovers = []
for path in ("settings.gradle.kts", "build.gradle.kts", "plugin/src/main/resources/META-INF/plugin.xml"):
    text = Path(path).read_text()
    for needle in (':duplicates', ':ml-completion', 'org.rust.duplicates', 'org.rust.mlCompletion', 'useCache = true', 'org.jetbrains.grammarkit'):
        if needle in text:
            leftovers.append(f"{path}: {needle}")
if leftovers:
    raise SystemExit("262 patch left unsupported/stale entries:\n" + "\n".join(leftovers))
PY
