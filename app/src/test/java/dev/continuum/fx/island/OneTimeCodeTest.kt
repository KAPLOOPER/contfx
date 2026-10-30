package dev.continuum.fx.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneTimeCodeTest {
    private fun code(text: String, title: String = "") = OneTimeCode.find(title, text)

    @Test
    fun findsTheUsualShapes() {
        assertEquals("482913", code("482913 is your verification code."))
        assertEquals("482913", code("Your Instagram code is 482913. Don't share it."))
        assertEquals("4829", code("Your Uber code: 4829"))
        assertEquals("482913", code("G-482913 is your Google verification code."))
        assertEquals("123456", code("Use 123 456 to sign in to your account"))
        assertEquals("123456", code("Your WhatsApp code: 123-456"))
        assertEquals("12345678", code("Your one-time password is 12345678"))
        assertEquals("773310", code("PIN: 773310"))
        assertEquals("482913", code("Verify your email with 482913"))
        assertEquals("48291", code("Your Steam Guard code is 48291"))
        assertEquals("F7K2Q", code("Your Steam Guard code: F7K2Q"))
        assertEquals("482913", code("Tu código de verificación es 482913"))
        assertEquals("482913", code("Ваш код: 482913"))
        assertEquals("482913", code("【验证码】482913，5分钟内有效"))
    }

    @Test
    fun usesTheTitleToo() {
        assertEquals("552190", code("552190", title = "Your sign-in code"))
    }

    @Test
    fun picksTheNumberNearestTheCodeWords() {
        assertEquals("482913", code("Your code is 482913. Questions? Call (555) 123-4567 any time."))
        assertEquals("904117", code("Order 1234567 update: use code 904117 to collect your parcel"))
    }

    @Test
    fun leavesMessagesWithoutCodesAlone() {
        assertNull(code("Your order 123456 has been confirmed and will ship tomorrow."))
        assertNull(code("Running five minutes late, save me a seat"))
        assertNull(code("Meet at 10:30 by gate 12"))
        assertNull(code("Your package #482913 is out for delivery"))
    }

    @Test
    fun passesOverMoneyTimesYearsAndLinks() {
        assertNull(code("Your code for $1500 off expires soon"))
        assertNull(code("Use code SAVE20 at checkout, 2024 deals"))
        assertNull(code("Verification of your 2024 tax return is complete"))
        assertNull(code("Login from 192.168.1.10 on Chrome"))
        assertNull(code("Your verification link: https://x.co/482913"))
        assertNull(code("Spend 4500 € and get a promo code"))
        assertEquals("2024", code("Your code: 2024"))
    }
}
