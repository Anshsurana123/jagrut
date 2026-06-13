// Required Notice: Copyright 2026 Ansh. (https://github.com/Anshsurana123/jago)
package com.example.jago.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {

    private val parser = CommandParser()

    @Test
    fun testParseMultiCommand() {
        val input = "turn on flashlight and open whatsapp"
        val commands = parser.parse(input)
        assertEquals(2, commands.size)
        assertEquals(CommandType.FLASHLIGHT_ON, commands[0].type)
        assertEquals(CommandType.OPEN_WHATSAPP, commands[1].type)
    }

    @Test
    fun testNumericDisambiguationTo() {
        val input = "increase volume to 70"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.VOLUME_UP, cmd.type)
        assertEquals(70, cmd.numericValue)
        assertEquals(false, cmd.isRelative) // 'to' should force absolute
    }

    @Test
    fun testNumericDisambiguationBy() {
        val input = "increase volume by 20"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.VOLUME_UP, cmd.type)
        assertEquals(20, cmd.numericValue)
        assertEquals(true, cmd.isRelative) // 'by' should force relative
    }

    @Test
    fun testCloseAppIntent() {
        val input = "close whatsapp"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        assertEquals(CommandType.CLOSE_APP, commands[0].type)
        assertEquals("whatsapp", commands[0].contactName)
    }

    @Test
    fun testMinimizeSynonym() {
        val input = "minimize youtube"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        assertEquals(CommandType.CLOSE_APP, commands[0].type)
        assertEquals("youtube", commands[0].contactName)
    }

    @Test
    fun testQueryBrightness() {
        val input = "what is the current brightness level"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        assertEquals(CommandType.QUERY_BRIGHTNESS, commands[0].type)
    }

    @Test
    fun testReminderFull() {
        val input = "remind me to study at 8 pm"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SET_REMINDER, cmd.type)
        assertEquals("study", cmd.messageBody)
        assertEquals(false, cmd.missingTime)
        assertEquals(false, cmd.missingMessage)
        assert(cmd.triggerMillis != null)
    }

    @Test
    fun testReminderRelative() {
        val input = "remind me in 10 minutes to drink water"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SET_REMINDER, cmd.type)
        assertEquals("drink water", cmd.messageBody)
        assertEquals(false, cmd.missingTime)
        assert(cmd.formattedTime?.contains("10") == true)
    }

    @Test
    fun testReminderMissingTime() {
        val input = "remind me to study"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SET_REMINDER, cmd.type)
        assertEquals("study", cmd.messageBody)
        assertEquals(true, cmd.missingTime)
    }

    @Test
    fun testReminderMissingMessage() {
        val input = "remind me at 6 pm"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SET_REMINDER, cmd.type)
        assertEquals(true, cmd.missingMessage)
        assertEquals(false, cmd.missingTime)
    }

    @Test
    fun testReminderInvalidTime() {
        val input = "remind me at banana"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SET_REMINDER, cmd.type)
        assertEquals(true, cmd.missingTime)
    }

    @Test
    fun testReminderAfterKeyword() {
        val input = "remind me after 30 minutes to take medicine"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SET_REMINDER, cmd.type)
        assertEquals("take medicine", cmd.messageBody)
        assertEquals(false, cmd.missingTime)
        assert(cmd.triggerMillis != null)
    }

    @Test
    fun testReminderWithinKeyword() {
        val input = "remind me within 1 hour to check email"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SET_REMINDER, cmd.type)
        assertEquals("check email", cmd.messageBody)
        assertEquals(false, cmd.missingTime)
    }

    @Test
    fun testReminderMissingUnit() {
        val input = "remind me in 30 to leave"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SET_REMINDER, cmd.type)
        assertEquals("leave", cmd.messageBody)
        assertEquals(true, cmd.missingTimeUnit)
    }

    @Test
    fun testVideoAndSend() {
        val input = "take a 10 second video and send it to John"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.TAKE_VIDEO_AND_SEND, cmd.type)
        assertEquals(10, cmd.numericValue)
        assertEquals("john", cmd.contactName)
    }

    @Test
    fun testVideoAndSendAlternate() {
        val input = "record a video of 15 seconds and send to dad on WhatsApp"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.TAKE_VIDEO_AND_SEND, cmd.type)
        assertEquals(15, cmd.numericValue)
        assertEquals("dad", cmd.contactName)
    }

    @Test
    fun testPhotoAndSend() {
        val input = "take a photo and send it to mom"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.TAKE_PHOTO_AND_SEND, cmd.type)
        assertEquals("mom", cmd.contactName)
    }

    @Test
    fun testSendWhatsAppMessage() {
        val input = "send hello how are you to sister on WhatsApp"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SEND_WHATSAPP_MESSAGE, cmd.type)
        assertEquals("hello how are you", cmd.messageBody)
        assertEquals("sister", cmd.contactName)
    }

    @Test
    fun testPlaySpotify() {
        val input = "play believer on Spotify"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.PLAY_SPOTIFY, cmd.type)
        assertEquals("believer", cmd.messageBody)
    }

    @Test
    fun testSearchOnYouTube() {
        val input = "search funny cat videos on YouTube"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SEARCH, cmd.type)
        assertEquals("funny cat videos", cmd.messageBody)
        assertEquals("youtube", cmd.searchPlatform)
    }

    @Test
    fun testSearchOnGoogle() {
        val input = "search distance to moon on google"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SEARCH, cmd.type)
        assertEquals("distance to moon", cmd.messageBody)
        assertEquals("google", cmd.searchPlatform)
    }

    @Test
    fun testTelegramMessageWithSaying() {
        val input = "send telegram message to phraser saying hello"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SEND_TELEGRAM_MESSAGE, cmd.type)
        assertEquals("phraser", cmd.contactName)
        assertEquals("hello", cmd.messageBody)
    }

    @Test
    fun testTelegramMessageNoSaying() {
        val input = "send telegram to phraser"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SEND_TELEGRAM_MESSAGE, cmd.type)
        assertEquals("phraser", cmd.contactName)
        assertEquals(null, cmd.messageBody)
    }

    @Test
    fun testEmailMessageDirectSaying() {
        val input = "mail anshsuran01 i am testing an app"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SEND_EMAIL, cmd.type)
        assertEquals("anshsuran01", cmd.contactName)
        assertEquals("i am testing an app", cmd.messageBody)
    }

    @Test
    fun testEmailMessageDirectSayingWithSpaceNumber() {
        val input = "mail anshsuran 01 i am testing an app"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SEND_EMAIL, cmd.type)
        assertEquals("anshsuran01", cmd.contactName)
        assertEquals("i am testing an app", cmd.messageBody)
    }

    @Test
    fun testEmailMessageDirectSayingWithEmail() {
        val input = "mail anshsuran01@gmail.com i am testing an app"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SEND_EMAIL, cmd.type)
        assertEquals("anshsuran01@gmail.com", cmd.contactName)
        assertEquals("i am testing an app", cmd.messageBody)
    }

    @Test
    fun testEmailNoSaying() {
        val input = "mail anshsuran01"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SEND_EMAIL, cmd.type)
        assertEquals("anshsuran01", cmd.contactName)
        assertEquals(null, cmd.messageBody)
    }

    @Test
    fun testEmailNoSayingWithSpaceNumber() {
        val input = "mail anshsuran 01"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals(CommandType.SEND_EMAIL, cmd.type)
        assertEquals("anshsuran01", cmd.contactName)
        assertEquals(null, cmd.messageBody)
    }

    @Test
    fun testRemoveProfilePictureBypassesLocalParser() {
        val input = "remove my profile picture from whatsapp"
        val commands = parser.parse(input)
        assertEquals(1, commands.size)
        assertEquals(CommandType.UNKNOWN, commands[0].type)
    }
}
