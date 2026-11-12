package com.blogPlatform.III.blog.dto.request;

import com.blogPlatform.III.blog.validation.TagName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record TagRequest(
        @Schema(example = "spring", description = "1-50 characters; starts with a letter or digit; letters, digits, spaces and . _ + # -")
        @NotBlank @TagName String name) {
}
