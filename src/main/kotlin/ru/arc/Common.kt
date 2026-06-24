package ru.arc

import com.google.gson.Gson
import com.google.gson.GsonBuilder

object Common {
    @JvmField
    val gson: Gson = GsonBuilder().create()

    @JvmField
    val prettyGson: Gson = GsonBuilder().setPrettyPrinting().create()
}
