package com.aicode.lancmd

import com.aicode.lancmd.net.joinPath
import com.aicode.lancmd.net.parentOf
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {

    @Test
    fun windowsPathJoin() {
        assertEquals("C:\\Users\\a.txt", joinPath("C:\\Users", "a.txt"))
        assertEquals("C:\\Users\\a.txt", joinPath("C:\\Users\\", "a.txt"))
        assertEquals("C:\\a.txt", joinPath("C:\\", "a.txt"))
    }

    @Test
    fun posixPathJoin() {
        assertEquals("/home/u/a.txt", joinPath("/home/u", "a.txt"))
        assertEquals("/home/u/a.txt", joinPath("/home/u/", "a.txt"))
        assertEquals("/a.txt", joinPath("/", "a.txt"))
    }

    @Test
    fun parentPaths() {
        assertEquals("C:\\Users", parentOf("C:\\Users\\a.txt"))
        assertEquals("C:\\", parentOf("C:\\Users"))
        assertEquals("/home", parentOf("/home/u"))
        assertEquals("/", parentOf("/a"))
    }
}
