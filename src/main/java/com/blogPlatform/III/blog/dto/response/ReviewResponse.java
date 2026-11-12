package com.blogPlatform.III.blog.dto.response;

import com.blogPlatform.III.blog.entity.Review;

import java.time.LocalDateTime;

public record ReviewResponse(Long id, Long postId, Long userId, String username,
                             Integer rating, String reviewText, LocalDateTime createdAt) {
    public static ReviewResponse from(Review review) {
        return new ReviewResponse(review.getId(), review.getPost().getId(),
                review.getUser().getId(), review.getUser().getUsername(),
                review.getRating(), review.getReviewText(), review.getCreatedAt());
    }
}
