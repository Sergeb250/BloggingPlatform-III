package com.blogPlatform.III.blog.dto.response;

import com.blogPlatform.III.blog.entity.AuditLog;

import java.time.LocalDateTime;

public record AuditLogResponse(Long id, String action, Long entityId, String details, LocalDateTime createdAt) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getAction(), log.getEntityId(),
                log.getDetails(), log.getCreatedAt());
    }
}
