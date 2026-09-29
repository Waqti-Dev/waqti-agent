package com.waqti.agent.tools

import org.junit.Assert

/**
 * Kotlin-friendly (condition, message) assertion overloads.
 * JUnit's order is (message, condition), which reads backwards in Kotlin code.
 */
internal fun assertTrue(condition: Boolean, message: String) {
    Assert.assertTrue(message, condition)
}

internal fun assertFalse(condition: Boolean, message: String) {
    Assert.assertFalse(message, condition)
}
