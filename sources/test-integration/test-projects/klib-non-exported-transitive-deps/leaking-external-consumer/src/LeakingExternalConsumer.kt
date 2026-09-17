import kotlinx.datetime.LocalDate

/**
 * 'LocalDate' comes from a non-exported external dependency of 'facade-lib', so this must not compile
 */
fun leakExternal(): Int = kotlinx.datetime.LocalDate(2026, 1, 1).year
