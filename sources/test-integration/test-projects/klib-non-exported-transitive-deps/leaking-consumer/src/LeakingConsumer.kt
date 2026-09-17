// 'Model' comes from a non-exported transitive dependency, so this must not compile
fun leak(): String = Model("leaked").name
