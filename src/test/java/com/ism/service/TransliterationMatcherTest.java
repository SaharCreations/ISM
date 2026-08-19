package com.ism.service;

import com.ism.model.MatchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class TransliterationMatcherTest {

    private final TransliterationMatcher matcher = new TransliterationMatcher();

    @ParameterizedTest
    @CsvSource({
            "Mohammed,Muhammad",
            "Mohamed,Mohammad",
            "Yousef,Yusuf",
            "Khaled,Khalid",
            "Sameer,Samir",
            "Abdelrahman,Abdulrahman",
            "Ahmed,Ahmad",
            "Fatima,Fatimah"
    })
    void knownVariantsShouldMatch(String a, String b) {
        MatchResult result = matcher.match(a, b);
        assertTrue(result.match(), () -> a + " / " + b + " scored " + result.score());
        assertTrue(result.score() >= 0.82);
        assertFalse(result.reasons().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "Ali,Omar",
            "Hassan,Hamza",
            "Karim,Khalid",
            "Salim,Samir",
            "Nadia,Nabil",
            "Mariam,Mahmoud",
            "Yusuf,Younes",
            "Ahmed,Hamid"
    })
    void distinctNamesShouldNotMatch(String a, String b) {
        MatchResult result = matcher.match(a, b);
        assertFalse(result.match(), () -> a + " / " + b + " incorrectly scored " + result.score());
    }

    @Test
    void resultShouldBeExplainable() {
        MatchResult result = matcher.match("Mohammed", "Muhammad");
        assertNotNull(result.normalizedA());
        assertNotNull(result.normalizedB());
        assertNotNull(result.consonantFrameA());
        assertFalse(result.reasons().isEmpty());
        assertFalse(result.differences().isEmpty());
    }
}
