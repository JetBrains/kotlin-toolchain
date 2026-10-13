package com.jetbrains.sample.lib

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.parcelableCreator

@Parcelize
data class User(val firstName: String, val lastName: String, val age: Int) : Parcelable {

    companion object {
        fun fromParcel(parcel: Parcel): User = parcelableCreator<User>().createFromParcel(parcel)
    }
}
