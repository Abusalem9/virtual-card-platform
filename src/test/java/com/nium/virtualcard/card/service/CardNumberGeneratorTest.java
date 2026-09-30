package com.nium.virtualcard.card.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CardNumberGeneratorTest {
    private final CardNumberGenerator generator = new CardNumberGenerator();

    @Test
    void generatesSixteenDigitLuhnValidNumbers() {
        for (int i = 0; i < 10_000; i++) {
            long number = generator.next();

            assertEquals(16, Long.toString(number).length(), "length of " + number);
            assertTrue(CardNumberGenerator.isValid(number), "luhn of " + number);
        }
    }

    @Test
    void luhnValidationRecognisesKnownNumbers() {
        assertTrue(CardNumberGenerator.isValid(4539148803436467L));
        assertTrue(CardNumberGenerator.isValid(4111111111111111L));
        assertFalse(CardNumberGenerator.isValid(4111111111111112L));
        assertFalse(CardNumberGenerator.isValid(411111111111111L));
    }

    @Test
    void numbersDiffer() {
        assertNotEquals(generator.next(), generator.next());
    }
}
