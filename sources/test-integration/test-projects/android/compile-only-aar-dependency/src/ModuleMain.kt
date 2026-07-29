package com.sample.app

import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Invoker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.FileNotFoundException
import java.io.FileReader

class ModuleMain : XposedModule() {
    companion object {
        const val TAG = "XposedExample"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) { }

    override fun onPackageLoaded(param: PackageLoadedParam) { }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        val prefs = getRemotePreferences("test")
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            val value = prefs.getInt(key, 0)
            log(Log.INFO, TAG, "onSharedPreferenceChanged: $key->$value")
        }
        try {
            val text = openRemoteFile("test.txt").use {
                FileReader(it.fileDescriptor).readText()
            }
        } catch (_: FileNotFoundException) {
            log(Log.INFO, TAG, "remote file not found")
        }

        val exampleClass = Class.forName("io.github.libxposed.example.Example", true, param.classLoader)
        val exampleMethod = exampleClass.getDeclaredMethod("method")
        val exampleConstructor = exampleClass.getDeclaredConstructor()

        hook(exampleMethod).intercept { chain ->
            log(Log.INFO, TAG, "call the following chains with the same args")
            var result = chain.proceed() as String

            log(Log.INFO, TAG, "call the following chains with different args")
            val old0 = chain.args[0] as String
            val new1 = Any()
            val newArgs = arrayOf(old0, new1);
            result += chain.proceed(newArgs) as String

            log(Log.INFO, TAG, "call the following chains with different this object")
            val newThis = Any()
            result += chain.proceedWith(newThis) as String
            result += chain.proceedWith(newThis, newArgs) as String

            log(Log.INFO, TAG, "call the raw method")
            result += getInvoker(exampleMethod).setType(Invoker.Type.ORIGIN).invoke(chain.getThisObject()) as String

            log(Log.INFO, TAG, "returned value will pass to higher priority chains")
            result
        }

        hook(exampleMethod).intercept { chain ->
            chain.proceed()
        }

        hook(exampleConstructor)
            .setPriority(PRIORITY_HIGHEST)
            .setExceptionMode(ExceptionMode.PASSTHROUGH)
            .intercept { _ ->
                log(Log.INFO, TAG, "thrown exception will be propagated to upper interceptors or the caller")
                throw RuntimeException("constructor hook exception")
            }

        // call the original method
        getInvoker(exampleMethod).setType(Invoker.Type.ORIGIN).invoke(Any())
        // call the special method starting from the middle of the hook chain
        getInvoker(exampleMethod).setType(Invoker.Type.Chain(-50)).invokeSpecial(Any())
        // create a new instance using the original constructor
        getInvoker(exampleConstructor).setType(Invoker.Type.ORIGIN).newInstance()
        // create a new special instance with full hook chain
        getInvoker(exampleConstructor).setType(Invoker.Type.Chain.FULL).newInstanceSpecial(exampleClass)
        // identical to the above line, default to call with full hook chain
        getInvoker(exampleConstructor).newInstanceSpecial(exampleClass)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) { }
}
