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
# Add the explicit platform modules to every project dependency set.
marker = '            bundledModule("intellij.spellchecker")\n'
modules = '''            bundledModule("intellij.spellchecker")
            bundledModule("intellij.platform.testRunner")
            bundledModule("intellij.platform.structuralSearch")
            bundledModule("intellij.platform.structureView")
            bundledModule("intellij.platform.navbar")
'''
if marker in s and 'bundledModule("intellij.platform.testRunner")' not in s:
    s = s.replace(marker, modules, 1)

p.write_text(s)
PY

grep -nE 'testRunner|structuralSearch|structureView|navbar|VERSION_25|JVM_25|kotlin\("jvm"\)|grammarkit|tomlPlugin|intellijIdea\(|IDEA_BUILD_NUMBER' build.gradle.kts || true
