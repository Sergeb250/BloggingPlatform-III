package com.blogPlatform.III.blog.dto.response;

public interface PopularPostView {
    Long getPostId();
    String getTitle();
    Long getCommentCount();
    Long getReviewCount();
    Double getAverageRating();
    Double getScore();
}
