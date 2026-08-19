#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

p = Path("build.gradle.kts")
s = p.read_text()

replacements = {
    'val tomlPlugin = "org.toml.lang:262.9437.22"': 'val tomlPlugin: String by project',
    'id("org.jetbrains.intellij.platform") version "2.13.1"': 'id("org.jetbrains.intellij.platform") version "2.18.1"',
    'environment("IDEA_BUILD_NUMBER", "253")': 'environment("IDEA_BUILD_NUMBER", "262")',
    '            bundledPlugins(listOf(tomlPlugin))': '            plugins(listOf(tomlPlugin))',
    'import org.gradle.api.JavaVersion.VERSION_21': 'import org.gradle.api.JavaVersion.VERSION_25',
    'sourceCompatibility = VERSION_21': 'sourceCompatibility = VERSION_25',
    'targetCompatibility = VERSION_21': 'targetCompatibility = VERSION_25',
    'jvmTarget.set(JvmTarget.JVM_21)': 'jvmTarget.set(JvmTarget.JVM_25)',
}

for old, new in replacements.items():
    if old in s:
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

# ML ranking is no longer a bundled plugin in IDEA 2026.2. If an old
# reference survived in the root build script, remove only that dependency.
s = s.replace('val mlCompletionPlugin = "com.intellij.completion.ml.ranking"\n', '')
s = s.replace('                javaScriptPlugin,\n                mlCompletionPlugin\n', '                javaScriptPlugin\n')
s = s.replace('            bundledPlugins(listOf(mlCompletionPlugin))\n', '')

p.write_text(s)
PY

# Verify the critical 262 migration points before Gradle starts.
grep -nE 'VERSION_25|JVM_25|org.jetbrains.intellij.platform|tomlPlugin|intellijIdea\(|IDEA_BUILD_NUMBER|mlCompletionPlugin|bundledPlugins\(listOf\(tomlPlugin\)\)' build.gradle.kts || true
