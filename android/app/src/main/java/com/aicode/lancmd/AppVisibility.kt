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
    private val created=Collections.newSetFromMap(IdentityHashMap<Activity,Boolean>())
    private val listeners=LinkedHashSet<()->Unit>()
    private val handler=Handler(Looper.getMainLooper())
    private val background=Runnable { if(started.isEmpty())listeners.toList().forEach { it() } }

    fun install(app:Application) {
        if(application===app)return
        application?.unregisterActivityLifecycleCallbacks(this)
        handler.removeCallbacks(background);started.clear();created.clear();listeners.clear()
        application=app;app.registerActivityLifecycleCallbacks(this)
    }
    fun observe(owner:LifecycleOwner, onBackground:()->Unit) {
        listeners.add(onBackground)
        owner.lifecycle.addObserver(object:DefaultLifecycleObserver {
            override fun onDestroy(owner:LifecycleOwner) { listeners.remove(onBackground) }
        })
    }
    override fun onActivityStarted(activity:Activity) {
        // install() may run from an Activity after its created callback (for
        // example a restored entry point); starting also establishes ownership.
        created.add(activity);started.add(activity);handler.removeCallbacks(background)
    }
    override fun onActivityStopped(activity:Activity) {
        started.remove(activity)
        if(started.isEmpty()) {
            // A finishing child can stop before its retained parent restarts on a
            // slow device. Keep leases during this bounded internal return handoff.
            val returning = activity.isFinishing && created.any {
                it !== activity && !it.isFinishing && !it.isDestroyed
            }
            handler.removeCallbacks(background)
            handler.postDelayed(background,if(returning || activity.isChangingConfigurations) 5000 else 700)
        }
    }
    override fun onActivityDestroyed(activity:Activity) { started.remove(activity);created.remove(activity) }
    override fun onActivityCreated(activity:Activity,state:Bundle?) { created.add(activity) }
    override fun onActivityResumed(activity:Activity)=Unit
    override fun onActivityPaused(activity:Activity)=Unit
    override fun onActivitySaveInstanceState(activity:Activity,state:Bundle)=Unit
}
