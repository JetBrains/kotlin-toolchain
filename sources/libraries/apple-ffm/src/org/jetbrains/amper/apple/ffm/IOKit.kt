/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.apple.ffm

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

/**
 * Downcalls into the IOKit framework.
 */
object IOKit {

    /**
     * When specifying a primary port to IOKit functions, the NULL argument indicates "use the default".
     * This is a synonym for NULL, if you'd rather use a named constant.
     */
    const val kIOMasterPortDefault = 0

    private val lookup: SymbolLookup = SymbolLookup.libraryLookup(
        "/System/Library/Frameworks/IOKit.framework/IOKit",
        Arena.global(),
    )

    private val IOServiceMatching: MethodHandle = lookup.downcall(
        "IOServiceMatching", FunctionDescriptor.of(ADDRESS, ADDRESS),
    )
    private val IOServiceGetMatchingService: MethodHandle = lookup.downcall(
        "IOServiceGetMatchingService", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS),
    )
    private val IORegistryEntryCreateCFProperty: MethodHandle = lookup.downcall(
        "IORegistryEntryCreateCFProperty", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
    )
    private val IOObjectRelease: MethodHandle = lookup.downcall(
        "IOObjectRelease", FunctionDescriptor.of(JAVA_INT, JAVA_INT),
    )

    typealias CFDictionary = MemorySegment

    /**
     * Create a matching dictionary that specifies an IOService class match.
     *
     * @param name The class name, as a const C-string. Class matching is successful on IOService's of this
     *   class or any subclass.
     *
     * @return The matching dictionary created, is returned on success, or zero on failure. The dictionary is commonly
     *   passed to IOServiceGetMatchingServices or IOServiceAddNotification which will consume a reference, otherwise
     *   it should be released with CFRelease by the caller.
     */
    fun IOServiceMatching(name: MemorySegment): CFDictionary =
        IOServiceMatching.invokeExact(name) as MemorySegment

    /**
     * Look up a registered IOService object that matches a matching dictionary.
     *
     * @param masterPort The primary port obtained from IOMasterPort(_:_:). Pass kIOMasterPortDefault to look up the
     *   default primary port.
     * @param matching A CF dictionary containing matching information, of which one reference is always consumed by
     *   this function (Note prior to the Tiger release there was a small chance that the dictionary might not be
     *   released if there was an error attempting to serialize the dictionary). IOKitLib can construct matching
     *   dictionaries for common criteria with helper functions such as IOServiceMatching, IOServiceNameMatching,
     *   IOBSDNameMatching.
     *
     * @return The IOService object found, or zero on failure. The object is commonly passed to IOServiceOpen or
     *   IOObjectRelease which will consume a reference, otherwise it should be released with IOObjectRelease by the
     *   caller.
     */
    fun IOServiceGetMatchingService(masterPort: Int = kIOMasterPortDefault, matching: CFDictionary): Int =
        IOServiceGetMatchingService.invokeExact(masterPort, matching) as Int

    /**
     * Creates a CF representation of a registry entry's property.
     *
     * This function creates an instantaneous snapshot of a registry entry property, creating a CF container analogue
     * in the caller's task. Not every object available in the kernel is represented as a CF container; currently
     * OSDictionary, OSArray, OSSet, OSSymbol, OSString, OSData, OSNumber, OSBoolean are created as their CF
     * counterparts.
     *
     * @param entry The registry entry handle whose property to copy.
     * @param key A CFString specifying the property name.
     * @param allocator The CF allocator to use when creating the CF container. Pass NULL to use the default allocator.
     * @param options No options are currently defined.
     *
     * @return The CF container created, or NULL if the entry has no property with the given name. The caller should
     *   release it with CFRelease.
     */
    fun IORegistryEntryCreateCFProperty(
        entry: Int,
        key: MemorySegment,
        allocator: MemorySegment = MemorySegment.NULL,
        options: Int = 0,
    ): MemorySegment = IORegistryEntryCreateCFProperty.invokeExact(entry, key, allocator, options) as MemorySegment

    /**
     * Releases an object handle previously returned by IOKitLib.
     *
     * All objects returned by IOKitLib should be released with this function when access to them is no longer needed.
     * Using the object after it has been released may or may not return an error, depending on how many references the
     * task has to the same object in the kernel.
     *
     * @param obj The IOKit object to release.
     *
     * @return A kern_return_t error code.
     */
    fun IOObjectRelease(obj: Int): Int = IOObjectRelease.invokeExact(obj) as Int
}

/**
 * Looks up the IOService matching the given [serviceName], and runs [block] with it.
 * The service is released after [block] returns, normally or exceptionally.
 */
context(arena: Arena)
inline fun <T> IOKit.withService(serviceName: String, block: (serviceId: Int) -> T): T {
    val serviceName = arena.allocateFrom(serviceName)
    // IOServiceGetMatchingService consumes the reference of the matching dictionary, so no need to release the
    // dictionary ourselves
    val serviceMatching = IOServiceMatching(serviceName)
    val service = IOServiceGetMatchingService(matching = serviceMatching)
    try {
        return block(service)
    } finally {
        IOObjectRelease(service)
    }
}
