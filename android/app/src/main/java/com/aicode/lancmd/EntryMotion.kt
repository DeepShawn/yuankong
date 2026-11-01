package com.aicode.lancmd

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/** Window transitions keep content visible/interactive and leave connection work alone. */
internal object EntryMotion {
    fun enabled(ctx:Context,restored:Boolean)=!restored && runCatching {
        Settings.Global.getFloat(ctx.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f &&
            Settings.Global.getFloat(ctx.contentResolver,Settings.Global.TRANSITION_ANIMATION_SCALE,1f)>0f
    }.getOrDefault(false)
    @Suppress("DEPRECATION")
    fun enter(activity:Activity,state:Bundle?) {
        if(!enabled(activity,state!=null))return
        if(Build.VERSION.SDK_INT>=34)activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN,R.anim.workspace_enter,R.anim.workspace_leave)
        else activity.overridePendingTransition(R.anim.workspace_enter,R.anim.workspace_leave)
    }
}
