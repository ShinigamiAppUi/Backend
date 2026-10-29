package com.baseprovider.streamix.jvm

import com.baseprovider.streamix.StreamixJson
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class JvmStreamixJson(
    private val gson: Gson = Gson()
) : StreamixJson {
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
    private val listType = object : TypeToken<List<Any?>>() {}.type

    override fun parseObject(json: String): Map<String, Any?> =
        gson.fromJson<Map<String, Any?>>(json, mapType) ?: emptyMap()

    override fun parseArray(json: String): List<Any?> =
        gson.fromJson<List<Any?>>(json, listType) ?: emptyList()

    override fun stringify(value: Any?): String = gson.toJson(value)
}
