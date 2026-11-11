package org.dedussamosval.alya.data

import android.content.Context
import org.dedussamosval.alya.BuildConfig

object Updates {
    fun isNewer(remote: String, local: String): Boolean {
        fun parse(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val r = parse(remote); val l = parse(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}