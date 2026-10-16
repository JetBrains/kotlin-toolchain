package {{PROJECT_ID}}

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
