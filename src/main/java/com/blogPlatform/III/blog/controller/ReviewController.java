package com.blogPlatform.III.blog.controller;

import com.blogPlatform.III.blog.dto.request.ReviewRequest;
import com.blogPlatform.III.blog.dto.response.ApiResponse;
import com.blogPlatform.III.blog.dto.response.PageResponse;
import com.blogPlatform.III.blog.dto.response.ReviewResponse;
import com.blogPlatform.III.blog.service.ReviewService;
import com.blogPlatform.III.blog.validation.ValidPageable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Reviews", description = "Ratings (1-5) of one post")
@RestController
@RequestMapping("/api/posts/{postId}/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "Review a post",
            description = "Rating 1-5. A user can review a post only once. "
                    + "400: invalid body. 404: post or user not found. 409: this user already reviewed this post.")
    @PostMapping
    public ResponseEntity<ApiResponse<ReviewResponse>> add(@PathVariable @Positive Long postId,
                                                           @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.created("Review added successfully", reviewService.add(postId, request));
    }

    @Operation(summary = "List the reviews of a post (paged)",
            description = "Default: page 0, 10 reviews, newest first. size: 1-100. Sort fields: id, rating, createdAt. "
                    + "400: invalid size or sort. 404: post not found.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ReviewResponse>>> getByPost(
            @PathVariable @Positive Long postId,
            @ParameterObject
            @ValidPageable(sortFields = {"id", "rating", "createdAt"})
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok("Reviews retrieved successfully", reviewService.getByPost(postId, pageable));
    }

    @Operation(summary = "Delete a review (soft delete)",
            description = "The row is kept and marked with deleted_at; it no longer counts in ratings. "
                    + "The same user can review the post again. 404: review not found on this post, or already deleted.")
    @DeleteMapping("/{reviewId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable @Positive Long postId, @PathVariable @Positive Long reviewId) {
        reviewService.delete(postId, reviewId);
        return ApiResponse.ok("Review deleted successfully", null);
    }
}
