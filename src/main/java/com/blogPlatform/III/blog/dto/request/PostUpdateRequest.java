package com.blogPlatform.III.blog.dto.request;

import com.blogPlatform.III.blog.entity.PostStatus;
import com.blogPlatform.III.blog.validation.TagName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PostUpdateRequest(
        @Schema(example = "Getting started with Spring Data JPA (updated)") @NotBlank @Size(min = 3, max = 200) String title,
        @Schema(example = "New text of the post.") @NotBlank @Size(max = 10_000) String content,
        @Schema(example = "PUBLISHED", description = "optional, keeps the old status if missing") PostStatus status,

        @Schema(example = "[\"java\"]", description = "optional, keeps the old tags if missing; max 10 tags, each 1-50 characters (letters, digits, spaces, . _ + # -)")
        @Size(max = 10, message = "a post can have at most 10 tags")
        List<@NotBlank @TagName String> tags,

        @Schema(example = "0", description = "the version you read; an old version -> 409")
        @NotNull @PositiveOrZero Long version
) {
}
