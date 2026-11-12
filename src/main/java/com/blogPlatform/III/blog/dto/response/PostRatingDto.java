package com.blogPlatform.III.blog.dto.response;

public record PostRatingDto(Long postId, String title, Double averageRating, Long reviewCount) {
}
