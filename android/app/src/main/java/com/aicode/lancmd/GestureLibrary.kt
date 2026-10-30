package com.aicode.lancmd

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Atomic local storage; neither a PC connection nor Activity state owns the library. */
internal class GestureLibrary(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.filesDir, "gesture-library.json"))
    companion object {
        private val lock = Any()
        private val io = Executors.newSingleThreadExecutor { task -> Thread(task,"gesture-storage").apply { isDaemon=true } }.asCoroutineDispatcher()
        private val writes = CoroutineScope(SupervisorJob() + io)
    }

    private fun read(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            val legacy = app.getSharedPreferences("gestures", Context.MODE_PRIVATE).getString("saved", "[]")
            return JSONObject().put("saved", JSONArray(legacy))
        }
        return file.openRead().use { stream ->
            check(file.baseFile.length() <= 16 * 1024 * 1024) { "手势库过大，请保留原文件后检查" }
            val bytes = stream.readBytes()
            check(bytes.size <= 16 * 1024 * 1024) { "手势库过大，请保留原文件后检查" }
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }
    private fun write(data: JSONObject) {
        val stream = file.startWrite()
        try { stream.write(data.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
    suspend fun load(): JSONObject = withContext(io) { synchronized(lock) { read() } }
    suspend fun draft(value: JSONObject) = withContext(io) {
        synchronized(lock) { write(read().put("draft", value)) }
    }
    fun enqueueDraft(value: JSONObject, onError: (Exception) -> Unit) = writes.launch {
        try { synchronized(lock) { write(read().put("draft", value)) } }
        catch(error: Exception) { onError(error) }
    }
    suspend fun add(name: String, value: JSONObject) = withContext(io) {
        synchronized(lock) {
            val data = read(); val saved = data.optJSONArray("saved") ?: JSONArray()
            check(saved.length() < 20) { "手势列表已满，请先删除旧手势" }
            saved.put(JSONObject(value.toString()).put("name", name).put("saved_at", System.currentTimeMillis()))
            write(data.put("saved", saved).put("draft", value))
        }
    }
    suspend fun remove(index: Int) = withContext(io) {
        synchronized(lock) {
            val data = read(); val saved = data.optJSONArray("saved") ?: JSONArray()
            check(index in 0 until saved.length()) { "手势列表已变化，请重新打开" }
            saved.remove(index); write(data.put("saved", saved))
        }
    }
}
