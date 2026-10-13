package com.jetbrains.sample.lib

import android.os.Parcel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

@RunWith(RobolectricTestRunner::class)
class UserTest {

    @Test
    fun `user survives parcel round trip`() {
        val original = User(firstName = "Jane", lastName = "Doe", age = 42)

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = User.fromParcel(parcel)
            assertEquals(original, restored)
        } finally {
            parcel.recycle()
        }
    }
}
