package com.ism.controller;

import com.ism.model.MatchRequest;
import com.ism.model.MatchResult;
import com.ism.service.TransliterationMatcher;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MatchController {

    private final TransliterationMatcher matcher;

    public MatchController(TransliterationMatcher matcher) {
        this.matcher = matcher;
    }

    @PostMapping("/match")
    public ResponseEntity<MatchResult> match(@Valid @RequestBody MatchRequest request) {
        return ResponseEntity.ok(matcher.match(request.nameA(), request.nameB()));
    }
}
