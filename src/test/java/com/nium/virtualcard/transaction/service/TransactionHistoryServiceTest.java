package com.nium.virtualcard.transaction.service;

import com.nium.virtualcard.card.exception.CardNotFoundException;
import com.nium.virtualcard.card.repository.CardRepository;
import com.nium.virtualcard.transaction.entity.CardTransaction;
import com.nium.virtualcard.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionHistoryServiceTest {
    @Mock
    CardRepository cardRepository;
    @Mock
    TransactionRepository transactionRepository;
    @InjectMocks
    TransactionHistoryService service;

    Long cardId = 4539148803436467L;

    @Test
    void returnsRequestedPage() {
        var pageable = PageRequest.of(0, 20);
        var entry = CardTransaction.issuance(UUID.randomUUID(), cardId, new BigDecimal("10.00"), Instant.now());
        when(cardRepository.existsById(cardId)).thenReturn(true);
        when(transactionRepository.findByCardId(cardId, pageable)).thenReturn(new PageImpl<>(List.of(entry), pageable, 1));

        var page = service.history(cardId, pageable);

        assertEquals(1, page.getTotalElements());
        assertEquals(entry, page.getContent().get(0));
    }

    @Test
    void unknownCardIsNotFound() {
        when(cardRepository.existsById(cardId)).thenReturn(false);

        assertThrows(CardNotFoundException.class, () -> service.history(cardId, PageRequest.of(0, 20)));
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void pageSizeIsCappedAndNegativePageClamped() {
        var pageable = TransactionHistoryService.pageable(-3, 1000, false);

        assertEquals(0, pageable.getPageNumber());
        assertEquals(100, pageable.getPageSize());
        assertEquals("createdAt: DESC,id: DESC", pageable.getSort().toString());
    }

    @Test
    void tooSmallPageSizeBecomesOne() {
        assertEquals(1, TransactionHistoryService.pageable(0, 0, false).getPageSize());
    }

    @Test
    void allReturnsOneBoundedPageIgnoringClientPaging() {
        var pageable = TransactionHistoryService.pageable(4, 5, true);

        assertEquals(0, pageable.getPageNumber());
        assertEquals(10_000, pageable.getPageSize());
    }

    @Test
    void appliesPagingPolicyBeforeQuerying() {
        when(cardRepository.existsById(cardId)).thenReturn(true);
        var expected = TransactionHistoryService.pageable(0, 100, false);
        when(transactionRepository.findByCardId(cardId, expected)).thenReturn(new PageImpl<>(List.of(), expected, 0));

        service.history(cardId, -1, 5000, false);

        verify(transactionRepository).findByCardId(cardId, expected);
    }
}
