package com.intempt.core.types

/**
 * Which named SDK instance a component belongs to.
 *
 * The SDK was a singleton: one Dagger graph, one set of `SharedPreferences` names, one SQLite
 * queue file. That makes two Intempt projects in one app impossible and makes tests share state
 * with each other, which is why the cross-SDK contract says a singleton is not conformant.
 *
 * A Dagger `@Component` already gives one graph per `initialize()` call, so what was actually
 * missing is scoping on **disk**. Without it two instances write to the same prefs and the same
 * queue, and the second inherits the first's `profileId` and posts the first's events under its
 * own credentials — which looks like working software and is silent data corruption.
 *
 * Carried as a type rather than a bare `String` so Dagger needs no qualifier annotation at every
 * injection site, and so a stray `String` binding can never be mistaken for this one.
 */
@JvmInline
internal value class InstanceId(val name: String) {
    /**
     * The storage name for [base], scoped to this instance.
     *
     * [DEFAULT] is deliberately **not** suffixed. It is the only instance a single-project app
     * ever has, so leaving its names bare gives the common case exactly the storage layout it had
     * before named instances existed — no migration, and no queue full of undelivered events
     * orphaned under a name nothing reads any more.
     */
    fun scope(base: String): String = if (name == DEFAULT) base else "${base}_$name"

    companion object {
        const val DEFAULT = "default"

        val Default = InstanceId(DEFAULT)
    }
}
