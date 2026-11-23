package com.ridebooking.auth.api;

import com.ridebooking.auth.api.dto.UserRequest;
import com.ridebooking.auth.api.dto.UserResponse;
import com.ridebooking.auth.api.mapper.UserMapper;
import com.ridebooking.auth.application.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class UserController {

    private final UserService service;
    private final UserMapper mapper;

    public UserController(UserService service, UserMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody UserRequest request) {
        return mapper.toResponse(service.register(mapper.toUser(request)));
    }
}
