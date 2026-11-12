package com.blogPlatform.III.blog.controller;

import com.blogPlatform.III.blog.dto.response.ApiResponse;
import com.blogPlatform.III.blog.dto.response.StatsResponse;
import com.blogPlatform.III.blog.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stats", description = "Dashboard numbers")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @Operation(summary = "Platform statistics",
            description = "Counts of users, posts, comments, reviews, tags and the average rating, all read from one "
                    + "consistent database snapshot (REPEATABLE_READ).")
    @GetMapping
    public ResponseEntity<ApiResponse<StatsResponse>> getStats() {
        return ApiResponse.ok("Stats retrieved successfully", statsService.getStats());
    }
}
