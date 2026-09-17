/**
 * 'facade-lib' internally depends on 'model-lib',
 * which is therefore absent from this consumer compilation classpath.
 * Compiling against 'facade-lib' must still work without any KLIB resolution diagnostics.
 */
fun consumeFacadeOnly(): String = facade()
