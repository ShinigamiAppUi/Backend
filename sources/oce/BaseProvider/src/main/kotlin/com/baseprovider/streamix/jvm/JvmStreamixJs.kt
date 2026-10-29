package com.baseprovider.streamix.jvm

import com.baseprovider.streamix.StreamixJs
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** JVM JS bridge backed by Rhino; isolated from provider code. */
class JvmStreamixJs : StreamixJs {
    override fun evaluate(script: String): String {
        val context = Context.enter()
        return try {
            context.optimizationLevel = -1
            val scope: Scriptable = context.initSafeStandardObjects()
            ScriptableObject.putProperty(scope, "window", scope)
            Context.toString(context.evaluateString(scope, script, "streamix", 1, null))
        } finally {
            Context.exit()
        }
    }
}
