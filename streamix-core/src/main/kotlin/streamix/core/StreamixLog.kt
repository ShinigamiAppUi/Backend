package streamix.core

interface StreamixLog {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, error: Throwable? = null)
}

object StreamixLogger : StreamixLog {
    override fun d(tag: String, message: String) = System.err.println("D/$tag: $message")
    override fun w(tag: String, message: String) = System.err.println("W/$tag: $message")
    override fun e(tag: String, message: String, error: Throwable?) {
        System.err.println("E/$tag: $message")
        error?.printStackTrace(System.err)
    }
}
