package com.aicode.lancmd

import kotlin.math.min

data class RemotePoint(val x: Float, val y: Float)
data class RemoteFrame(val left: Float, val top: Float, val width: Float, val height: Float)

/** FIT_CENTER coordinates; touches in the black bars never become desktop clicks. */
object RemoteTouch {
    fun frame(viewWidth: Int, viewHeight: Int, imageWidth: Int, imageHeight: Int): RemoteFrame? {
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
        val scale = min(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        return RemoteFrame((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
    }
    fun point(x: Float, y: Float, viewWidth: Int, viewHeight: Int,
              imageWidth: Int, imageHeight: Int, clamp: Boolean = false): RemotePoint? {
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0 ||
            !x.isFinite() || !y.isFinite()) return null
        val frame = frame(viewWidth, viewHeight, imageWidth, imageHeight) ?: return null
        val nx = (x - frame.left) / frame.width
        val ny = (y - frame.top) / frame.height
        if (!clamp && (nx < 0f || nx > 1f || ny < 0f || ny > 1f)) return null
        return RemotePoint(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
    }
}
