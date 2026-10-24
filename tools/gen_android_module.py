#!/usr/bin/env python3
"""Writes a consistent build.gradle.kts for an Android library module.

Usage: gen_android_module.py <module dir> <namespace> [--no-compose] [--ksp] [--ser] dep...
Each dep is either a project path (":core:ocr") or a catalog alias prefixed with "libs." and
optionally a configuration, e.g. "api::core:money-time" or "testImplementation=libs.junit".
"""
import sys, os

args = sys.argv[1:]
mod, ns = args[0], args[1]
flags = {a for a in args[2:] if a.startswith('--')}
deps = [a for a in args[2:] if not a.startswith('--')]
compose = '--no-compose' not in flags
lines = ['plugins {', '    id("com.android.library")', '    id("org.jetbrains.kotlin.android")']
if compose: lines.append('    id("org.jetbrains.kotlin.plugin.compose")')
if '--ksp' in flags: lines.append('    id("com.google.devtools.ksp")')
if '--ser' in flags: lines.append('    id("org.jetbrains.kotlin.plugin.serialization")')
lines += ['}', '', 'android {', f'    namespace = "{ns}"', '    compileSdk = 36', '', '    defaultConfig {', '        minSdk = 26',
          '        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"', '        consumerProguardFiles("consumer-rules.pro")', '    }', '',
          '    compileOptions {', '        sourceCompatibility = JavaVersion.VERSION_17', '        targetCompatibility = JavaVersion.VERSION_17', '    }']
if compose: lines += ['', '    buildFeatures {', '        compose = true', '    }']
lines += ['', '    testOptions {', '        unitTests.isIncludeAndroidResources = true', '        unitTests.isReturnDefaultValues = true', '    }', '}', '',
          'kotlin {', '    compilerOptions {', '        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)',
          '        optIn.addAll("androidx.compose.material3.ExperimentalMaterial3Api", "androidx.compose.foundation.ExperimentalFoundationApi", "androidx.compose.foundation.layout.ExperimentalLayoutApi")' if compose else '',
          '    }', '}', '', 'dependencies {']
if compose:
    lines += ['    api(platform(libs.compose.bom))', '    implementation(libs.compose.ui)', '    implementation(libs.compose.foundation)',
              '    implementation(libs.compose.material3)', '    implementation(libs.compose.material.icons)', '    implementation(libs.compose.ui.tooling.preview)',
              '    debugImplementation(libs.compose.ui.tooling)']
for d in deps:
    conf = 'implementation'
    if '=' in d:
        conf, d = d.split('=', 1)
    elif d.startswith('api:'):
        conf, d = 'api', d[4:]
    if d.startswith('libs.'):
        lines.append(f'    {conf}({d})')
    else:
        lines.append(f'    {conf}(project("{d}"))')
lines += ['    testImplementation(libs.junit)', '    testImplementation(libs.truth)', '}', '']
os.makedirs(mod, exist_ok=True)
open(os.path.join(mod, 'build.gradle.kts'), 'w').write('\n'.join(lines))
rules = os.path.join(mod, 'consumer-rules.pro')
if not os.path.exists(rules): open(rules, 'w').write('')
os.makedirs(os.path.join(mod, 'src/main'), exist_ok=True)
manifest = os.path.join(mod, 'src/main/AndroidManifest.xml')
if not os.path.exists(manifest):
    open(manifest, 'w').write('<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android" />\n')
