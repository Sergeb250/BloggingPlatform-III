package com.blogPlatform.III.blog.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CommentUpdateRequest(
        @Schema(example = "Edited comment") @NotBlank @Size(max = 2000) String content,
        @Schema(example = "0", description = "the version you read; an old version -> 409") @NotNull @PositiveOrZero Long version
) {
}
