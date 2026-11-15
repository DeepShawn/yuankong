package com.aicode.lancmd.net

import com.aicode.lancmd.Target

/** A missing explicitly selected peer must never become the first other responder. */
internal object TargetRouting {
    fun pick(last:Target?,found:List<AgentInfo>,selected:Boolean):AgentInfo? {
        val matching=found.firstOrNull{last!=null && last.agentId.isNotBlank() && it.id==last.agentId && it.port==last.port}
        if(selected)return matching
        return matching ?: found.firstOrNull()
    }
}
