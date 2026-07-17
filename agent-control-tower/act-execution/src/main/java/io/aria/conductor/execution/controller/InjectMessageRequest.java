package io.aria.conductor.execution.controller;

import jakarta.validation.constraints.NotBlank;

public record InjectMessageRequest(@NotBlank String content, String role) {}
