/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.kotlin.plugins

import org.jetbrains.amper.frontend.api.CanBeReferenced
import org.jetbrains.amper.frontend.api.KnownStringValues
import org.jetbrains.amper.frontend.api.SchemaDoc
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.api.Shorthand
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.frontend.tree.NullLiteralNode
import org.jetbrains.amper.frontend.tree.ReferenceNode
import org.jetbrains.amper.frontend.tree.RefinedTreeNode

const val legacySerializationFormatNone = "none"

class SerializationSettings : SchemaNode() {
    class EnabledTransform : ReferenceNode.TransformFunction<Boolean> {
        override fun transform(node: RefinedTreeNode): Boolean = node !is NullLiteralNode
    }

    @Shorthand
    @SchemaDoc("Enables the kotlinx.serialization compiler plugin, which generates code based on " +
            "@Serializable annotations. This also automatically adds the kotlinx-serialization-core library to " +
            "provide the annotations and facilities for serialization, but no specific serialization format.")
    // if a format is specified, we need to enable serialization (mostly to be backwards compatible)
    val enabled: Boolean by referenceValue(::format, "enabled when specified", EnabledTransform())

    @CanBeReferenced
    @Shorthand
    @SchemaDoc("The [kotlinx.serialization format](https://github.com/Kotlin/kotlinx.serialization/blob/master/formats/README.md) " +
            "to use, such as `json`. When set, the corresponding `kotlinx-serialization-<format>` library is " +
            "automatically added to dependencies. When null, no format dependency is automatically added. " +
            "Prefer using the built-in catalog dependencies for this, as it gives control over the 'scope' and " +
            "'exported' properties.")
    @KnownStringValues("json", "json-io", "json-okio", "hocon", "protobuf", "cbor", "properties", "none")
    val format by nullableValue<String>(default = null)

    @SchemaDoc("The version of the kotlinx.serialization core and format libraries to use.")
    val version by value(default = DefaultVersions.kotlinxSerialization)
}
