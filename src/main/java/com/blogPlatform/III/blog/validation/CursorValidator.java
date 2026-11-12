package com.blogPlatform.III.blog.validation;

import com.blogPlatform.III.blog.dto.request.FeedCursor;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CursorValidator implements ConstraintValidator<ValidCursor, String> {

    @Override
    public boolean isValid(String cursor, ConstraintValidatorContext context) {
        if (cursor == null || cursor.isEmpty()) {
            return true;
        }
        try {
            return FeedCursor.parse(cursor).id() > 0;
        } catch (RuntimeException notACursor) {
            return false;
        }
    }
}
