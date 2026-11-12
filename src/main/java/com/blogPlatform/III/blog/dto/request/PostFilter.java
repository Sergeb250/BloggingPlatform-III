package com.blogPlatform.III.blog.dto.request;

import com.blogPlatform.III.blog.entity.PostStatus;
import com.blogPlatform.III.blog.validation.TagName;
import com.blogPlatform.III.blog.validation.Username;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public record PostFilter(

        @Parameter(description = "DRAFT or PUBLISHED")
        PostStatus status,

        @Parameter(description = "author username (3-50 characters: letters, digits, . _ -)", example = "user7")
        @Username
        String author,

        @Parameter(description = "tag name, case-insensitive (1-50 characters)", example = "java")
        @TagName
        String tag,

        @Parameter(description = "whole word in title or content (FULLTEXT), 3-100 characters", example = "docker")
        @Size(min = 3, max = 100)
        String keyword,

        @Parameter(description = "created on or after (yyyy-MM-dd)", example = "2025-01-01")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate from,

        @Parameter(description = "created on or before (yyyy-MM-dd)", example = "2025-12-31")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate to
) {
    @AssertTrue(message = "'from' must be on or before 'to'")
    public boolean isDateRangeValid() {
        return from == null || to == null || !from.isAfter(to);
    }
}
