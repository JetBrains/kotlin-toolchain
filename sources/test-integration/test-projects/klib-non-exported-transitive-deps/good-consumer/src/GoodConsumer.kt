// 'facade()' comes from a direct dependency, and 'Model' from an exported transitive dependency,
// so both must be visible here.
fun consume(): String = facade() + exportedModel().name
