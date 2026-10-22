package com.aicode.lancmd.net

const val APP_ID = "LANCMD"
const val UDP_PORT = 47824
const val TCP_PORT = 47823
const val PROTO_VERSION = 1

data class AgentInfo(
    val ip: String,
    val name: String,
    val port: Int,
    val os: String,
    val autoAccept: Boolean,
    val id: String = ""
)

/** 依据基准路径判断分隔符，兼容 Windows 与类 Unix 接收端。 */
fun joinPath(base: String, name: String): String {
    if (base.isEmpty()) return name
    val sep = if (base.contains("\\")) "\\" else "/"
    return if (base.endsWith("/") || base.endsWith("\\")) base + name else base + sep + name
}

fun parentOf(path: String): String {
    val p = path.trimEnd('/', '\\')
    val idx = p.lastIndexOfAny(charArrayOf('/', '\\'))
    if (idx < 0) return p
    val head = p.substring(0, idx)
    if (head.isEmpty()) return "/"
    // Windows 盘符根：C: -> C:\
    if (head.length == 2 && head[1] == ':') return head + "\\"
    return head
}
