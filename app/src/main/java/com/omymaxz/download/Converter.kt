package com.omymaxz.download

    import androidx.room.TypeConverter
    import com.google.gson.Gson
    import com.google.gson.reflect.TypeToken
    import java.lang.reflect.Type

    class Converters {
        private val gson = Gson()
        private val listType: Type = object : TypeToken<List<String>>() {}.type

        @TypeConverter
        fun fromStringList(list: List<String>?): String? {
            return if (list == null) null else gson.toJson(list)
        }

        @TypeConverter
        fun toStringList(data: String?): List<String>? {
            return if (data == null) null else gson.fromJson(data, listType)
        }
    }