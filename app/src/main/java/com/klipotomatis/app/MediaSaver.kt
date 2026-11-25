package com.klipotomatis.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.IOException

object MediaSaver {

    /** Menyalin file ke galeri (Movies/KlipOtomatis) dan mengembalikan Uri-nya. */
    fun saveToGallery(context: Context, file: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/KlipOtomatis")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Gagal membuat entri di galeri.")
        val out = resolver.openOutputStream(uri) ?: throw IOException("Gagal menulis ke galeri.")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri
    }
}
