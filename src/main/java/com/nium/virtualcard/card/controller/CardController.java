package com.nium.virtualcard.card.controller;

import com.nium.virtualcard.card.dto.CardResponse;
import com.nium.virtualcard.card.dto.CreateCardCommand;
import com.nium.virtualcard.card.dto.CreateCardRequest;
import com.nium.virtualcard.card.dto.UpdateCardStatusRequest;
import com.nium.virtualcard.card.service.CardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/cards")
public class CardController {
    private final CardService cardService;

    @PostMapping
    public ResponseEntity<CardResponse> create(@Valid @RequestBody CreateCardRequest request) {
        var card = cardService.create(
                new CreateCardCommand(request.cardholderName(), request.initialBalance(), request.expiresAt()));
        return ResponseEntity
                .created(URI.create("/api/v1/cards/" + card.getId()))
                .body(CardResponse.from(card));
    }

    @GetMapping("/{cardId}")
    public CardResponse get(@PathVariable Long cardId) {
        return CardResponse.from(cardService.get(cardId));
    }

    @PatchMapping("/{cardId}/status")
    public CardResponse updateStatus(@PathVariable Long cardId, @Valid @RequestBody UpdateCardStatusRequest request) {
        return CardResponse.from(cardService.updateStatus(cardId, request.status()));
    }
}
