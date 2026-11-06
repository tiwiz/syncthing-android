package com.nutomic.syncthingandroid.testutil

import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.reflector.Accessor
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector

/**
 * Records the thread of every `SharedPreferences.Editor.commit()` and the keys it changed, then
 * commits as usual. Lets a test check that a blocking write stays off the main thread (#153).
 */
@Implements(className = "android.app.SharedPreferencesImpl\$EditorImpl", isInAndroidSdk = false)
class ShadowSharedPreferencesCommits {

    // Robolectric injects the editor after construction, so this cannot be a val.
    @Suppress("VarCouldBeVal") @RealObject private lateinit var realEditor: SharedPreferences.Editor

    @Implementation
    fun commit(): Boolean {
        val modified = reflector(EditorReflector::class.java, realEditor).modified
        commits += Commit(Thread.currentThread(), synchronized(modified) { modified.keys.toSet() })
        return reflector(EditorReflector::class.java, realEditor).commit()
    }

    /** One commit: the thread it ran on and the keys it put or removed. */
    data class Commit(val thread: Thread, val keys: Set<String>)

    @ForType(className = "android.app.SharedPreferencesImpl\$EditorImpl")
    private interface EditorReflector {
        @get:Accessor("mModified") val modified: Map<String, Any?>

        @Direct fun commit(): Boolean
    }

    companion object {
        /** Commits since the last [reset], oldest first. */
        val commits: MutableList<Commit> = CopyOnWriteArrayList()

        fun reset() = commits.clear()
    }
}
