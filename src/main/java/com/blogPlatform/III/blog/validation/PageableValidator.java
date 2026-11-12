package com.blogPlatform.III.blog.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

public class PageableValidator implements ConstraintValidator<ValidPageable, Pageable> {

    private List<String> sortFields;
    private int maxSize;

    @Override
    public void initialize(ValidPageable annotation) {
        sortFields = List.of(annotation.sortFields());
        maxSize = annotation.maxSize();
    }

    @Override
    public boolean isValid(Pageable pageable, ConstraintValidatorContext context) {
        if (pageable == null || pageable.isUnpaged()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        boolean valid = true;

        if (pageable.getPageSize() > maxSize) {
            addError(context, "page size must be between 1 and " + maxSize);
            valid = false;
        }

        for (Sort.Order order : pageable.getSort()) {
            if (!sortFields.contains(order.getProperty())) {
                addError(context, sortFields.isEmpty()
                        ? "sorting is not allowed here"
                        : "cannot sort by '" + safe(order.getProperty()) + "'; allowed: " + String.join(", ", sortFields));
                valid = false;
            }
        }
        return valid;
    }

    private static void addError(ConstraintValidatorContext context, String message) {
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
    }

    // the message is a template ({...}, ${...}), so only harmless characters from the client go into it
    private static String safe(String clientValue) {
        return clientValue.replaceAll("[^A-Za-z0-9_.]", "?");
    }
}
