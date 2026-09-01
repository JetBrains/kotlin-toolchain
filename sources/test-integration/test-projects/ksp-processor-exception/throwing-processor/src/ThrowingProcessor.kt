package repro

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

class ThrowingProcessor : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        throw IllegalStateException("intentional KSP processor failure (repro)")
    }
}

class ThrowingProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = ThrowingProcessor()
}