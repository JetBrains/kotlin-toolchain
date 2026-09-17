// Uses Model internally only, so consumers still need it in the runtime (link) closure
fun facade(): String = Model("hidden").name
