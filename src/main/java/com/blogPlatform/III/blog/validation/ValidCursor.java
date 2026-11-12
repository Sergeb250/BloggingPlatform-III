package com.blogPlatform.III.blog.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = CursorValidator.class)
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCursor {

    String message() default "must be the nextCursor value of the previous response";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
