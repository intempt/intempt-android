package com.intempt.intempt_android

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

@Serializable
open class ToStringManager {

    @OptIn(InternalSerializationApi::class)
    inline fun <reified T : ToStringManager> toJson(): String {
        return Json.encodeToString(T::class.serializer(), this as T)
    }

    override fun toString(): String{
        return Json.encodeToString(this)
    }
}


//@OptIn(InternalSerializationApi::class)
//inline fun <reified T : ToStringManager> toJson(): String {
//    return Json.encodeToString(T::class.serializer(), this as T)
//}
//
//override fun toString(): String{
//    return toJson<ToStringManager>()
//}