package com.nium.virtualcard.transaction.service;

import com.nium.virtualcard.card.exception.CardNotFoundException;
import com.nium.virtualcard.card.repository.CardRepository;
import com.nium.virtualcard.transaction.entity.CardTransaction;
import com.nium.virtualcard.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the transaction module, kept apart from the write path so each can evolve (or scale) separately.
 * Owns the paging policy: callers pass what the client asked for, this class decides what is allowed.
 */
@RequiredArgsConstructor
@Service
public class TransactionHistoryService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    /**
     * Upper bound for "give me everything", so one huge card cannot exhaust memory.
     */
    static final int MAX_ALL_SIZE = 10_000;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final CardRepository cardRepository;
    private final TransactionRepository transactionRepository;

    static Pageable pageable(int page, int size, boolean all) {
        if (all) {
            return PageRequest.of(0, MAX_ALL_SIZE, NEWEST_FIRST);
        }
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), NEWEST_FIRST);
    }

    /**
     * @param all when true, returns the whole history (up to {@link #MAX_ALL_SIZE}) as a single page and
     *            ignores {@code page} and {@code size}
     */
    @Transactional(readOnly = true)
    public Page<CardTransaction> history(Long cardId, int page, int size, boolean all) {
        return history(cardId, pageable(page, size, all));
    }

    @Transactional(readOnly = true)
    public Page<CardTransaction> history(Long cardId, Pageable pageable) {
        if (!cardRepository.existsById(cardId)) {
            throw new CardNotFoundException(cardId);
        }
        return transactionRepository.findByCardId(cardId, pageable);
    }
}
