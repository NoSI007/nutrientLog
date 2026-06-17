package com.example.data

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter

object ResilientDoubleAdapter : JsonAdapter<Double>() {
    override fun fromJson(reader: JsonReader): Double? {
        val peek = reader.peek()
        if (peek == JsonReader.Token.NULL) {
            reader.nextNull<Unit>()
            return 0.0
        }
        if (peek == JsonReader.Token.STRING) {
            val s = reader.nextString()
            return s.toDoubleOrNull() ?: 0.0
        }
        if (peek == JsonReader.Token.NUMBER) {
            return reader.nextDouble()
        }
        reader.skipValue()
        return 0.0
    }

    override fun toJson(writer: JsonWriter, value: Double?) {
        writer.value(value ?: 0.0)
    }
}
