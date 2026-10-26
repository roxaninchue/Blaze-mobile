package com.blaze.assistant.agent

import kotlinx.coroutines.test.runTest

/**
 * 0.46.3 (CI run5): some objects launch on Dispatchers.Main from their initialiser (Updater, BankMode). Plain JVM unit
 * tests have no main looper, so that launch fails; once any runTest has run in the JVM, kotlinx-coroutines-test collects
 * such failures and throws them from the NEXT runTest as "uncaught exceptions before the test started", whichever class
 * that happens to be. Test classes that use runTest call [drain] in @Before: it drops only those known missing-Main
 * failures from other tests and rethrows anything else, so a real leak still fails.
 */
object LeakedMainLaunches {
    private fun missingMain(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            val m = e.message.orEmpty()
            if ("Main dispatcher" in m || "main looper" in m || "Dispatchers.Main" in m) return true
            if (e.suppressed.any { missingMain(it) }) return true
            e = e.cause
        }
        return false
    }

    fun drain() {
        // UncaughtExceptionsBeforeTest is internal in kotlinx-coroutines-test: matched by name
        try { runTest { } } catch (e: IllegalStateException) {
            if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
            val other = e.suppressed.filterNot { missingMain(it) }
            if (other.isNotEmpty()) { other.forEach { it.printStackTrace() }; throw e }
            System.err.println("LeakedMainLaunches: dropped ${e.suppressed.size} missing-Main launch failure(s) from earlier tests")
        }
    }
}
