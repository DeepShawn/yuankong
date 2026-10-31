package com.aicode.lancmd.net

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A caller-local progress sink; another page's pending task cannot overwrite it. */
class DelayNotice(val update:(String)->Unit):AbstractCoroutineContextElement(Key) {
    companion object Key:CoroutineContext.Key<DelayNotice>
}
