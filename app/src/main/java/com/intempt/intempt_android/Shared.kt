package com.intempt.intempt_android

import java.util.UUID


fun generateId(type: String? = null):String{
    val uuid = UUID.randomUUID().toString();

    return type?.let { it + "_" + uuid} ?: uuid
}


