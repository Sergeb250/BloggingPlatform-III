package com.blogPlatform.III.blog.dto.response;

public record StatsResponse(
        long users,
        long posts,
        long publishedPosts,
        long draftPosts,
        long comments,
        long reviews,
        long tags,
        double averageRating
) {
}
