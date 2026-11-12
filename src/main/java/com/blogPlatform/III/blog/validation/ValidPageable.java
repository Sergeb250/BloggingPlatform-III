package com.blogPlatform.III.blog.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PageableValidator.class)
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPageable {

    String[] sortFields();

    int maxSize() default 100;

    String message() default "invalid paging or sorting";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
