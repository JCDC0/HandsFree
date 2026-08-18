package com.example.handsfree

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // The applicationId, NOT the Kotlin package this file lives in.
        // They diverged deliberately in 1.1-tester1 — see app/build.gradle.kts.
        assertEquals("ph.edu.hau.handsfree", appContext.packageName)
    }
}