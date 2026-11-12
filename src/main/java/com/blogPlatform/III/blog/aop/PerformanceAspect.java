package com.blogPlatform.III.blog.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class PerformanceAspect {

    @Value("${app.performance.slow-threshold-ms:300}")
    private long slowThresholdMs;

    @Around("within(com.blogPlatform.III.blog.service..*) "
            + "|| execution(* org.springframework.data.repository.Repository+.*(..))")
    public Object measure(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.nanoTime();
        try {
            return joinPoint.proceed();
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String method = joinPoint.getSignature().toShortString();

            if (ms >= slowThresholdMs) {
                log.warn("SLOW {} took {} ms", method, ms);
            } else {
                log.debug("{} took {} ms", method, ms);
            }
        }
    }
}
