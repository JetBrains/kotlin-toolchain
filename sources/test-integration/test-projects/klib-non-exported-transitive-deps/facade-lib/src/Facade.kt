import kotlinx.datetime.LocalDate

/**
 * Uses non-exported dependencies internally only,
 * a consumers still need them in the runtime (link) closure,
 * but must not see (and don't need) their types during compilation.
 */
fun facade(): String = Model("hidden").name + LocalDate(2026, 1, 1).year
