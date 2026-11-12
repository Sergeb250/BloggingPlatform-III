package com.blogPlatform.III.blog.dto.response;

public record PopularPostResponse(
        Long postId,
        String title,
        Long commentCount,
        Long reviewCount,
        Double averageRating,
        Double score
) {
    public static PopularPostResponse from(PopularPostView view) {
        return new PopularPostResponse(view.getPostId(), view.getTitle(), view.getCommentCount(),
                view.getReviewCount(), view.getAverageRating(), view.getScore());
    }
}
