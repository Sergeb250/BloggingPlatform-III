package com.blogPlatform.III.blog.service;

import com.blogPlatform.III.blog.dto.response.AuditLogResponse;
import com.blogPlatform.III.blog.dto.response.PageResponse;
import com.blogPlatform.III.blog.entity.AuditLog;
import com.blogPlatform.III.blog.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    // separate bean on purpose: a call from inside PostService would skip the proxy and ignore REQUIRES_NEW
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, Long entityId, String details) {
        AuditLog log = new AuditLog();
        log.setAction(action);
        log.setEntityId(entityId);
        log.setDetails(details.length() > 500 ? details.substring(0, 500) : details);
        auditLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> getAll(Pageable pageable) {
        return PageResponse.from(auditLogRepository.findAll(pageable).map(AuditLogResponse::from));
    }
}
