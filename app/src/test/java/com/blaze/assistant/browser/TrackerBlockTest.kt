package com.blaze.assistant.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerBlockTest {
    @Test fun knownTrackersAndTheirSubdomainsAreBlocked() {
        listOf("doubleclick.net", "securepubads.g.doubleclick.net", "www.google-analytics.com", "connect.facebook.net", "mc.yandex.ru", "x.analytics.twitter.com", "STATIC.HOTJAR.COM.")
            .forEach { assertTrue(it, TrackerBlock.blocks(it)) }
    }

    @Test fun ordinarySitesAreNot() {
        listOf("google.com", "www.facebook.com", "twitter.com", "net", "", "news.ycombinator.com", "notdoubleclick.net", "yandex.ru")
            .forEach { assertFalse(it, TrackerBlock.blocks(it)) }
        assertFalse(TrackerBlock.blocks(null))
    }
}
