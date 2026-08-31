/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.stdlib.runtime

import kotlin.concurrent.thread

/**
 * Adds a JVM shutdown hook to run [block] when the JVM terminates, or calls [block] immediately in-place if the JVM is
 * already shutting down.
 *
 * Shutdown hooks are started at the beginning of the [shutdown sequence][Runtime].
 *
 * Uncaught exceptions are handled in shutdown hooks just as in any other thread, as specified in
 * [Thread.UncaughtExceptionHandler].
 *
 * ## Important considerations
 *
 * Shutdown hooks run at a delicate time in the life cycle of a virtual machine and should therefore be coded
 * defensively. They should, in particular:
 *
 *  * be thread-safe, and avoid deadlocks insofar as possible. Attempts to use other thread-based services such as the
 *    AWT event-dispatch thread, for example, may lead to deadlocks.
 *  * not rely blindly upon services that may have registered their own shutdown hooks and therefore may themselves be
 *    in the process of shutting down (**Logging is likely to fail!**).
 *  * finish their work quickly. When a program invokes {@link #exit exit}, the expectation is that the virtual machine
 *    will promptly shut down and exit. When the virtual machine is terminated due to user logoff or system shutdown the
 *    underlying operating system may only allow a limited amount of time in which to shut down and exit. It is
 *    therefore inadvisable to attempt any user interaction or to perform a long-running computation in a shutdown hook.
 */
fun runOnJvmShutdown(block: () -> Unit) {
    try {
        Runtime.getRuntime().addShutdownHook(
            thread(start = false) {
                block()
            }
        )
    } catch (_: IllegalStateException) {
        // IllegalStateException is thrown if the JVM is already shutting down (it's the only documented case).
        // In this case, we can't register the hook, but we generally still want to run the cleanup callback immediately.
        // Note: there is no direct way to check whether the JVM is shutting down before attempting to add the hook, so
        // we have to rely on this non-obvious exception mechanism.
        block()
    }
}
