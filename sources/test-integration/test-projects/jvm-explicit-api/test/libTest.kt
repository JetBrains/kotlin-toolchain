package apkg

// This class deliberately omits visibility modifiers and explicit return types: it would fail to compile if the
// module's strict explicit API mode was also applied to test sources.
class LibTest {
    fun greetingIsCorrect() = check(Lib().greeting() == "Hello, World!")
}
