/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

/**
 * Generates Package.swift manifest content for Swift Import synthetic packages.
 */
internal object SwiftPMImportManifestGenerator {
    /**
     * Generates the content of a Package.swift manifest file.
     *
     * @param identifier The package and target identifier
     * @param productType The product type string (e.g., ".dynamic" or ".none")
     * @param platforms List of platform strings (e.g., ".iOS(\"15.0\")")
     * @param repoDependencies List of package dependency declarations
     * @param targetDependencies List of target dependency declarations
     * @return The complete Package.swift manifest content
     */
    fun generateManifest(
        identifier: String,
        productType: String,
        platforms: List<String>,
        repoDependencies: List<String>,
        targetDependencies: List<String>,
        binaryTargets: List<String> = emptyList(),
    ): String = buildStringBlock(defaultIndent = "  ") {
        line("// swift-tools-version: 5.9")
        line("import PackageDescription")
        block("let package = Package(", ")") {
            commaSeparatedEntries {
                entry { line("name: \"$identifier\"") }
                entry {
                    block("platforms: [", "]") {
                        emitListItems(platforms)
                    }
                }
                entry {
                    block("products: [", "]") {
                        block(".library(", ")") {
                            commaSeparatedEntries {
                                entry { line("name: \"$identifier\"") }
                                entry { line("type: $productType") }
                                entry { line("targets: [\"$identifier\"]") }
                            }
                        }
                    }
                }
                entry {
                    block("dependencies: [", "]") {
                        emitListItems(repoDependencies)
                    }
                }
                entry {
                    block("targets: [", "]") {
                        commaSeparatedEntries {
                            entry {
                                block(".target(", ")") {
                                    commaSeparatedEntries {
                                        entry { line("name: \"$identifier\"") }
                                        entry {
                                            block("dependencies: [", "]") {
                                                emitListItems(targetDependencies)
                                            }
                                        }
                                    }
                                }
                            }
                            binaryTargets.forEach {
                                entry {
                                    line(it)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}