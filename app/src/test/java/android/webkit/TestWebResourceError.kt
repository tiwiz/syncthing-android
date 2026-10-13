package android.webkit

/**
 * [WebResourceError]'s constructor is package-private, so tests build one from inside its package.
 */
class TestWebResourceError(private val code: Int, private val description: String) :
    WebResourceError() {
    override fun getErrorCode(): Int = code

    override fun getDescription(): CharSequence = description
}
