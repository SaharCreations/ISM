package com.ism.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MatchRequest(
        @NotBlank @Size(max = 100) String nameA,
        @NotBlank @Size(max = 100) String nameB
) {}
