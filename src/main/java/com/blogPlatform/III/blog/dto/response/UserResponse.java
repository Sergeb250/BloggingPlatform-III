package com.blogPlatform.III.blog.dto.response;

import com.blogPlatform.III.blog.entity.Role;
import com.blogPlatform.III.blog.entity.User;

import java.time.LocalDateTime;

public record UserResponse(Long id, String username, String email, Role role, LocalDateTime createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(),
                user.getRole(), user.getCreatedAt());
    }
}
