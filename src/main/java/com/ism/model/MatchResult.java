package com.ism.model;

import java.util.List;

public record MatchResult(
        boolean match,
        double score,
        String band,
        String normalizedA,
        String normalizedB,
        String consonantFrameA,
        String consonantFrameB,
        List<String> reasons,
        List<Difference> differences
) {}
