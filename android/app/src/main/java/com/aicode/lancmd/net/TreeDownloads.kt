package com.aicode.lancmd.net

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** New SAF documents only. A failed/cancelled root removes only its own new tree. */
class TreeDownloads(private val context:Context,private val client:AgentClient) {
    private val resolver get()=context.contentResolver
    private var entries=0
    private fun checkConnection() {
        if(client.closed || runCatching { ConnectionManager.sessionClient() }.getOrNull()!==client)
            throw IOException("连接已切换，批量下载已停止")
    }
    private fun existingNames(parent:Uri):Set<String> {
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent))
        return resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { cursor->
            buildSet { while(cursor.moveToNext())add(cursor.getString(0)) }
        } ?: throw IOException("无法读取手机目标文件夹，未写入")
    }
    private fun create(parent:Uri,name:String,directory:Boolean):Uri {
        if(name.isBlank() || name in listOf(".","..") || name.any { it.code<32 || it=='/' || it=='\\' })throw IOException("文件名称无效")
        if(existingNames(parent).any { it.equals(name,true) })throw IOException("手机目标已存在，未覆盖：$name")
        return DocumentsContract.createDocument(resolver,parent,if(directory)DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",name)
            ?: throw IOException("无法创建手机文件：$name")
    }
    suspend fun download(path:String,item:JSONObject,tree:Uri,progress:(String,FileTransfer.Progress)->Unit):Uri=withContext(Dispatchers.IO) {
        entries=0
        checkConnection();coroutineContext.ensureActive()
        if(item.optBoolean("link"))throw IOException("不下载链接或重解析点")
        val parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        val root=create(parent,item.getString("name"),item.optBoolean("dir"))
        try {
            receive(path,item,root,0,progress)
            root
        } catch(e:Throwable) {
            var cleanup:Throwable?=null
            withContext(NonCancellable) {
                runCatching { if(!DocumentsContract.deleteDocument(resolver,root))throw IOException("手机提供方未确认清理") }.onFailure { cleanup=it }
            }
            if(cleanup!=null)e.addSuppressed(IOException("未完成手机文件清理失败：$root",cleanup))
            throw e
        }
    }
    private suspend fun receive(path:String,item:JSONObject,target:Uri,depth:Int,progress:(String,FileTransfer.Progress)->Unit) {
        checkConnection();coroutineContext.ensureActive()
        if(++entries>20000 || depth>64)throw IOException("下载目录超过 20000 项或 64 层，请分批处理")
        if(item.optBoolean("link"))throw IOException("不下载链接或重解析点：$path")
        if(item.optBoolean("dir")) {
            val result=client.call("ls",JSONObject().put("path",path))
            checkConnection()
            if(!result.optBoolean("ok"))throw IOException(result.optString("error","无法读取源文件夹"))
            val expected=item.optString("file_id")
            if(expected.isNotEmpty() && result.optString("file_id")!=expected)throw IOException("所选文件夹已变化，请刷新：$path")
            val children=result.getJSONArray("items")
            for(i in 0 until children.length()) {
                val child=children.getJSONObject(i)
                if(child.optBoolean("link"))throw IOException("目录包含链接，已停止：${child.optString("name")}")
                val destination=create(target,child.getString("name"),child.optBoolean("dir"))
                receive(joinPath(path,child.getString("name")),child,destination,depth+1,progress)
            }
        } else {
            FileTransfer.downloadToStream(path,client,{
                resolver.openOutputStream(target,"w") ?: throw IOException("无法写入手机文件")
            },{ progress(path,it) },item.optString("file_id").takeIf { it.isNotBlank() })
        }
    }
}
