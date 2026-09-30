package com.nium.virtualcard.card.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** 16-digit card numbers: 15 random digits (first non-zero) plus a Luhn check digit. Uniqueness is left to the primary key. */
@Component
public class CardNumberGenerator {
    private static final int BODY_DIGITS = 15;

    private final SecureRandom random = new SecureRandom();

    public static boolean isValid(long number) {
        String text = Long.toString(number);
        int sum = 0;
        for (int i = 0; i < text.length(); i++) {
            int digit = text.charAt(text.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return text.length() == BODY_DIGITS + 1 && sum % 10 == 0;
    }

    private static int luhnCheckDigit(int[] body) {
        int sum = 0;
        // the check digit takes position 0 from the right, so the last body digit is doubled
        for (int i = 0; i < body.length; i++) {
            int digit = body[body.length - 1 - i];
            if (i % 2 == 0) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return (10 - sum % 10) % 10;
    }

    public long next() {
        int[] digits = new int[BODY_DIGITS];
        digits[0] = 1 + random.nextInt(9);
        for (int i = 1; i < BODY_DIGITS; i++) {
            digits[i] = random.nextInt(10);
        }

        long number = 0;
        for (int digit : digits) {
            number = number * 10 + digit;
        }
        return number * 10 + luhnCheckDigit(digits);
    }
}
