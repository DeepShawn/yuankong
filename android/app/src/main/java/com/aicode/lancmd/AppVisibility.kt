package com.aicode.lancmd

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Collections
import java.util.IdentityHashMap

/** An internal Activity handoff keeps sharing; leaving the application stops it. */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    private var application: Application? = null
    private val started=Collections.newSetFromMap(IdentityHashMap<Activity,Boolean>())
    private val listeners=LinkedHashSet<()->Unit>()
    private val handler=Handler(Looper.getMainLooper())
    private val background=Runnable { if(started.isEmpty())listeners.toList().forEach { it() } }

    fun install(app:Application) {
        if(application===app)return
        application?.unregisterActivityLifecycleCallbacks(this)
        handler.removeCallbacks(background);started.clear();listeners.clear()
        application=app;app.registerActivityLifecycleCallbacks(this)
    }
    fun observe(owner:LifecycleOwner, onBackground:()->Unit) {
        listeners.add(onBackground)
        owner.lifecycle.addObserver(object:DefaultLifecycleObserver {
            override fun onDestroy(owner:LifecycleOwner) { listeners.remove(onBackground) }
        })
    }
    override fun onActivityStarted(activity:Activity) { started.add(activity);handler.removeCallbacks(background) }
    override fun onActivityStopped(activity:Activity) {
        started.remove(activity)
        if(started.isEmpty()) { handler.removeCallbacks(background);handler.postDelayed(background,700) }
    }
    override fun onActivityDestroyed(activity:Activity) { started.remove(activity) }
    override fun onActivityCreated(activity:Activity,state:Bundle?)=Unit
    override fun onActivityResumed(activity:Activity)=Unit
    override fun onActivityPaused(activity:Activity)=Unit
    override fun onActivitySaveInstanceState(activity:Activity,state:Bundle)=Unit
}
