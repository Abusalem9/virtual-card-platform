package com.nium.virtualcard.common.util;

import com.nium.virtualcard.common.exception.InvalidRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money is always scale 2; silently rounding a client's amount would move the wrong sum, so reject instead.
 */
public final class Amounts {
    private Amounts() {
    }

    public static BigDecimal normalize(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InvalidRequestException("Amounts must have at most 2 decimal places");
        }
    }
}
