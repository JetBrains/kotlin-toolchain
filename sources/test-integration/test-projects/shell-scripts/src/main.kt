fun main() {
    println("Hello for Shell Scripts Test")
    printEnvironmentVariable("KOTLIN_TOOLCHAIN_ENV_FILE_TEST", "Environment from project file")
    printEnvironmentVariable("KOTLIN_TOOLCHAIN_ENV_SHARED_TEST", "Environment from shared file")
    printEnvironmentVariable("KOTLIN_TOOLCHAIN_ENV_LAYER_TEST", "Environment layer")
    printEnvironmentVariable("KOTLIN_TOOLCHAIN_ENV_OVERRIDE_TEST", "Environment override")
}

private fun printEnvironmentVariable(name: String, label: String) {
    System.getenv(name)?.let { value -> println("$label: $value") }
}
