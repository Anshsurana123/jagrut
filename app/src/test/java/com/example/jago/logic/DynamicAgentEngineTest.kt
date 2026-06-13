// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class DynamicAgentEngineTest {

    @Test
    fun testTraverseAndCollect() {
        val rootNode = mock(AccessibilityNodeInfo::class.java)
        `when`(rootNode.isVisibleToUser).thenReturn(true)
        `when`(rootNode.isClickable).thenReturn(true)
        `when`(rootNode.className).thenReturn("android.widget.Button")
        `when`(rootNode.text).thenReturn("Submit")
        `when`(rootNode.childCount).thenReturn(0)

        val elements = mutableListOf<DynamicAgentEngine.ScreenElement>()
        DynamicAgentEngine.traverseAndCollect(rootNode, elements, 1)

        assertEquals(1, elements.size)
        val first = elements[0]
        assertEquals("Submit", first.text)
        assertEquals("android.widget.Button", first.className)
    }

    @Test
    fun testTraverseAndCollectMultiple() {
        val rootNode = mock(AccessibilityNodeInfo::class.java)
        `when`(rootNode.isVisibleToUser).thenReturn(true)
        `when`(rootNode.isClickable).thenReturn(false)
        `when`(rootNode.className).thenReturn("android.widget.LinearLayout")
        `when`(rootNode.childCount).thenReturn(2)

        val child1 = mock(AccessibilityNodeInfo::class.java)
        `when`(child1.isVisibleToUser).thenReturn(true)
        `when`(child1.isClickable).thenReturn(true)
        `when`(child1.className).thenReturn("android.widget.Button")
        `when`(child1.text).thenReturn("Button 1")
        `when`(child1.childCount).thenReturn(0)

        val child2 = mock(AccessibilityNodeInfo::class.java)
        `when`(child2.isVisibleToUser).thenReturn(true)
        `when`(child2.isClickable).thenReturn(true)
        `when`(child2.className).thenReturn("android.widget.TextView")
        `when`(child2.text).thenReturn("Clickable Text")
        `when`(child2.childCount).thenReturn(0)

        `when`(rootNode.getChild(0)).thenReturn(child1)
        `when`(rootNode.getChild(1)).thenReturn(child2)

        val elements = mutableListOf<DynamicAgentEngine.ScreenElement>()
        DynamicAgentEngine.traverseAndCollect(rootNode, elements, 1)

        assertEquals(2, elements.size)
        assertEquals("Button 1", elements[0].text)
        assertEquals("Clickable Text", elements[1].text)
    }
}
