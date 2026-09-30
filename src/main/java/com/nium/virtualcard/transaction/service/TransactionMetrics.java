package com.nium.virtualcard.transaction.service;

import com.nium.virtualcard.transaction.enums.TransactionType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class TransactionMetrics {
    private final MeterRegistry meterRegistry;

    public TransactionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(TransactionType type, String status, long nanos) {
        Counter.builder("virtual_card_transactions_total")
                .tag("type", type.name().toLowerCase())
                .tag("status", status.toLowerCase())
                .register(meterRegistry)
                .increment();

        Timer.builder("virtual_card_transaction_duration")
                .tag("type", type.name().toLowerCase())
                .register(meterRegistry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordReplay(TransactionType type) {
        Counter.builder("virtual_card_idempotent_replays_total")
                .tag("type", type.name().toLowerCase())
                .register(meterRegistry)
                .increment();
    }
}
