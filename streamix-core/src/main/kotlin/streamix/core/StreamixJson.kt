package streamix.core

interface StreamixJson {
    fun parseObject(json: String): Map<String, Any?>
    fun parseArray(json: String): List<Any?>
    fun stringify(value: Any?): String
}
