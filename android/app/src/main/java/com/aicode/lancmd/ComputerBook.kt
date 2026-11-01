package com.aicode.lancmd

import android.content.Context
import com.aicode.lancmd.net.AgentInfo
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Local inventory and connection history. Never stores credentials or controls routing. */
object ComputerBook {
    internal const val FILE="computer-book"
    internal const val MAX_COMPUTERS=100
    internal const val MAX_HISTORY=200
    data class Computer(val key:String,val host:String,val port:Int,val reportedName:String,val agentId:String,
        val alias:String="",val lastSeen:Long=0,val lastConnected:Long=0) {
        val label:String get()=alias.ifBlank{reportedName.ifBlank{host}}
        fun target()=Target(host,port,reportedName,agentId)
    }
    data class Visit(val id:String,val computerKey:String,val host:String,val port:Int,val reportedName:String,
        val started:Long,val ended:Long=0)
    data class Snapshot(val computers:List<Computer> = emptyList(),val history:List<Visit> = emptyList())
    private fun prefs(ctx:Context)=ctx.applicationContext.getSharedPreferences(FILE,Context.MODE_PRIVATE)
    private fun clean(value:String,max:Int)=value.filterNot{it.isISOControl()}.take(max).trim()
    private fun host(value:String)=clean(value,253).lowercase(Locale.ROOT).trimEnd('.')
    fun key(target:Target):String {
        val id=clean(target.agentId,256)
        val raw=(if(id.isNotBlank())"id\u0000$id" else "host\u0000${host(target.host)}")+"\u0000${target.port}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    }
    private fun normalized(t:Target):Target {
        require(t.host.isNotBlank() && t.host.length<=253 && t.host.none{it.isISOControl()} && t.port in 1..65535){"电脑地址或端口无效"}
        return t.copy(host=host(t.host),name=clean(t.name,80),agentId=clean(t.agentId,256))
    }
    @Synchronized fun read(ctx:Context):Snapshot {
        val raw=prefs(ctx).getString("book",null) ?: return Snapshot()
        try {
            require(raw.length<=512*1024)
            val root=JSONObject(raw);require(root.getInt("version")==1)
            val cs=root.getJSONArray("computers");val hs=root.getJSONArray("history")
            require(cs.length()<=MAX_COMPUTERS && hs.length()<=MAX_HISTORY)
            val computers=(0 until cs.length()).map{i->val c=cs.getJSONObject(i)
                val target=normalized(Target(c.getString("host"),c.getInt("port"),c.getString("name"),c.getString("agent_id")))
                Computer(key(target),target.host,target.port,target.name,target.agentId,clean(c.optString("alias"),40),
                    c.optLong("seen").coerceAtLeast(0),c.optLong("connected").coerceAtLeast(0))}
            require(computers.map{it.key}.distinct().size==computers.size)
            val history=(0 until hs.length()).map{i->val h=hs.getJSONObject(i)
                Visit(h.getString("id"),h.getString("key"),h.getString("host"),h.getInt("port"),clean(h.getString("name"),80),h.getLong("started"),h.optLong("ended"))}
            require(history.map{it.id}.distinct().size==history.size)
            require(history.all{it.id.isNotBlank() && it.id.length<=64 && it.computerKey.length==64 &&
                it.computerKey.all{c->c in "0123456789abcdef"} && it.host.isNotBlank() && it.host.length<=253 &&
                it.port in 1..65535 && it.started>0 && (it.ended==0L || it.ended>=it.started)})
            return Snapshot(computers,history)
        } catch(e:Exception){throw IllegalStateException("本地电脑记录无法读取，可在此页清空记录；配对凭据不受影响",e)}
    }
    private fun save(ctx:Context,s:Snapshot) {
        val computers=s.computers.sortedWith(compareByDescending<Computer>{it.lastConnected}.thenByDescending{it.alias.isNotBlank()}.thenByDescending{it.lastSeen}).take(MAX_COMPUTERS)
        val aliases=JSONObject();computers.forEach{if(it.alias.isNotBlank())aliases.put(it.key,it.alias)}
        val cs=JSONArray(computers.map{c->JSONObject().put("host",c.host).put("port",c.port).put("name",c.reportedName).put("agent_id",c.agentId)
            .put("alias",c.alias).put("seen",c.lastSeen).put("connected",c.lastConnected)})
        val hs=JSONArray(s.history.sortedByDescending{it.started}.take(MAX_HISTORY).map{v->JSONObject().put("id",v.id).put("key",v.computerKey)
            .put("host",v.host).put("port",v.port).put("name",v.reportedName).put("started",v.started).put("ended",v.ended)})
        check(prefs(ctx).edit().putString("book",JSONObject().put("version",1).put("computers",cs).put("history",hs).toString())
            .putString("aliases",aliases.toString()).putBoolean("seeded",true).commit()){"保存本地电脑记录失败"}
    }
    /** Small independent map for labels on the home screen; no connection state mutation. */
    fun displayName(ctx:Context,t:Target):String=runCatching{JSONObject(prefs(ctx).getString("aliases","{}")!!).optString(key(t)).ifBlank{t.name.ifBlank{t.host}}}
        .getOrDefault(t.name.ifBlank{t.host})
    private fun upsert(s:Snapshot,t:Target,seen:Long=0,connected:Long=0):Pair<Snapshot,Computer> {
        val target=normalized(t);val k=key(target)
        val previous=s.computers.firstOrNull{it.key==k} ?: s.computers.firstOrNull{
            target.agentId.isNotBlank() && it.agentId.isBlank() && it.host==target.host && it.port==target.port}
        val entry=Computer(k,target.host,target.port,target.name,target.agentId,previous?.alias.orEmpty(),
            maxOf(seen,previous?.lastSeen?:0),maxOf(connected,previous?.lastConnected?:0))
        val history=if(previous!=null && previous.key!=k)s.history.map{if(it.computerKey==previous.key)it.copy(computerKey=k)else it}else s.history
        return Snapshot(s.computers.filter{it.key!=k && it.key!=previous?.key}+entry,history) to entry
    }
    @Synchronized fun seed(ctx:Context):Snapshot {
        val s=read(ctx);if(s.computers.isNotEmpty() || prefs(ctx).getBoolean("seeded",false))return s
        val target=Prefs.target(ctx) ?: return s
        val result=upsert(s,target).first;save(ctx,result);return result
    }
    @Synchronized fun discovered(ctx:Context,agents:List<AgentInfo>,now:Long=System.currentTimeMillis()):Snapshot {
        var s=read(ctx)
        agents.take(MAX_COMPUTERS).forEach{a->
            if(a.ip.isNotBlank() && a.ip.length<=253 && a.ip.none{it.isISOControl()} && a.port in 1..65535)s=upsert(s,Target(a.ip,a.port,a.name,a.id),seen=now).first
        }
        save(ctx,s);return read(ctx)
    }
    @Synchronized fun rename(ctx:Context,target:Target,alias:String):Snapshot {
        require(alias.length<=40 && alias.none{it.isISOControl()}){"备注名最多 40 字，不能包含换行或控制字符"}
        val current=read(ctx)
        val (s,c)=current.computers.firstOrNull{it.key==key(target)}?.let{current to it} ?: upsert(current,target)
        save(ctx,s.copy(computers=s.computers.map{if(it.key==c.key)it.copy(alias=alias.trim())else it}));return read(ctx)
    }
    @Synchronized fun connected(ctx:Context,target:Target,now:Long=System.currentTimeMillis()):String {
        val at=now.coerceAtLeast(1L)
        val (s,c)=upsert(read(ctx),target,seen=at,connected=at)
        val visit=Visit(UUID.randomUUID().toString(),c.key,c.host,c.port,c.reportedName,at)
        save(ctx,s.copy(history=listOf(visit)+s.history));return visit.id
    }
    @Synchronized fun ended(ctx:Context,id:String,now:Long=System.currentTimeMillis()) {
        val s=read(ctx)
        if(s.history.none{it.id==id && it.ended==0L})return
        save(ctx,s.copy(history=s.history.map{if(it.id==id && it.ended==0L)it.copy(ended=maxOf(it.started,now))else it}))
    }
    @Synchronized fun removeVisit(ctx:Context,id:String):Snapshot {val s=read(ctx).let{it.copy(history=it.history.filterNot{v->v.id==id})};save(ctx,s);return s}
    @Synchronized fun clearHistory(ctx:Context):Snapshot {val s=read(ctx).copy(history=emptyList());save(ctx,s);return s}
    @Synchronized fun clear(ctx:Context):Snapshot {save(ctx,Snapshot());return Snapshot()}
}
