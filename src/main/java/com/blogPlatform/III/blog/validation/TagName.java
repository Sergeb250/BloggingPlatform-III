package com.blogPlatform.III.blog.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

// null is valid (optional filters); add @NotBlank where the value is required
@Documented
@Constraint(validatedBy = TagNameValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface TagName {

    String message() default "invalid tag name";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
