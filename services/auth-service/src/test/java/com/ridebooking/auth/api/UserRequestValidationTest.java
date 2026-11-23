package com.ridebooking.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridebooking.auth.api.dto.SignupRole;
import com.ridebooking.auth.api.dto.UserRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class UserRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validRequestHasNoViolations() {
        assertThat(validator.validate(new UserRequest("a@b.com", "S3cret-pass!", SignupRole.RIDER))).isEmpty();
    }

    @Test
    void rejectsBadEmailShortPasswordAndMissingRole() {
        assertThat(validator.validate(new UserRequest("nope", "S3cret-pass!", SignupRole.RIDER))).hasSize(1);
        assertThat(validator.validate(new UserRequest("a@b.com", "short", SignupRole.RIDER))).hasSize(1);
        assertThat(validator.validate(new UserRequest("a@b.com", "S3cret-pass!", null))).hasSize(1);
    }

    @Test
    void toStringNeverContainsPassword() {
        assertThat(new UserRequest("a@b.com", "S3cret-pass!", SignupRole.RIDER).toString())
                .doesNotContain("S3cret-pass!");
    }
}
