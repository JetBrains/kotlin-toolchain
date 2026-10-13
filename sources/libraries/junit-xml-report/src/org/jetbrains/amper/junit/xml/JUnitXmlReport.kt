/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.junit.xml

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream
import kotlin.time.Duration
import kotlin.time.DurationUnit

data class JUnitXmlTestCase(
    val name: String,
    val className: String,
    val duration: Duration,
    val result: JUnitXmlResult = JUnitXmlResult.Success,
    val stdout: String = "",
    val stderr: String = "",
)

sealed interface JUnitXmlResult {
    data object Success : JUnitXmlResult
    data class Failure(val message: String, val details: String = message) : JUnitXmlResult
    data class Error(val message: String, val details: String = message) : JUnitXmlResult
    data class Skipped(val reason: String) : JUnitXmlResult
}

data class JUnitXmlReport(
    val name: String,
    val tests: List<JUnitXmlTestCase>,
    val stdout: String = "",
    val stderr: String = "",
) {
    fun writeTo(path: Path) {
        val document = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().newDocument()
        val suite = document.createElement("testsuite")
        document.appendChild(suite)
        suite.attribute("name", name)
        suite.attribute("tests", tests.size.toString())
        suite.attribute("failures", tests.count { it.result is JUnitXmlResult.Failure }.toString())
        suite.attribute("errors", tests.count { it.result is JUnitXmlResult.Error }.toString())
        suite.attribute("skipped", tests.count { it.result is JUnitXmlResult.Skipped }.toString())
        suite.attribute("time", tests.fold(Duration.ZERO) { total, test -> total + test.duration }.seconds())
        tests.forEach { suite.appendChild(document.testCase(it)) }
        suite.output("system-out", stdout)
        suite.output("system-err", stderr)
        document.writeTo(path)
    }
}

/** Write the complete document before replacing the previous report. */
private fun Document.writeTo(path: Path) {
    val target = path.toAbsolutePath().createParentDirectories()
    val temporary = createTempFile(target.parent, ".junit-", ".xml")
    try {
        val transformer = TransformerFactory.newDefaultInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        temporary.outputStream().use { output ->
            transformer.transform(DOMSource(this), StreamResult(output))
        }
        try {
            temporary.moveTo(target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            temporary.moveTo(target, REPLACE_EXISTING)
        }
    } finally {
        temporary.deleteIfExists()
    }
}

private fun Document.testCase(test: JUnitXmlTestCase): Element {
    val element = createElement("testcase")
    element.attribute("name", test.name)
    element.attribute("classname", test.className)
    element.attribute("time", test.duration.seconds())
    when (val result = test.result) {
        JUnitXmlResult.Success -> Unit
        is JUnitXmlResult.Failure -> element.result("failure", result.message, result.details)
        is JUnitXmlResult.Error -> element.result("error", result.message, result.details)
        is JUnitXmlResult.Skipped -> element.result("skipped", result.reason, "")
    }
    element.output("system-out", test.stdout)
    element.output("system-err", test.stderr)
    return element
}

private fun Element.result(name: String, message: String, details: String) {
    val element = ownerDocument.createElement(name)
    element.attribute("message", message)
    element.textContent = details.xmlText()
    appendChild(element)
}

private fun Element.output(name: String, text: String) {
    if (text.isEmpty()) return
    val element = ownerDocument.createElement(name)
    element.textContent = text.xmlText()
    appendChild(element)
}

private fun Element.attribute(name: String, value: String) = setAttribute(name, value.xmlText())

private fun Duration.seconds(): String = toDouble(DurationUnit.SECONDS).toBigDecimal().stripTrailingZeros().toPlainString()

/** XML 1.0 cannot represent control characters or unpaired UTF-16 surrogates, even as character references. */
private fun String.xmlText(): String = buildString {
    for (index in this@xmlText.indices) {
        val char = this@xmlText[index]
        val valid = when {
            char == '\t' || char == '\n' || char == '\r' -> true
            char in '\u0020'..'\uD7FF' || char in '\uE000'..'\uFFFD' -> true
            char.isHighSurrogate() -> this@xmlText.getOrNull(index + 1)?.isLowSurrogate() == true
            char.isLowSurrogate() -> this@xmlText.getOrNull(index - 1)?.isHighSurrogate() == true
            else -> false
        }
        append(if (valid) char else '\uFFFD')
    }
}
