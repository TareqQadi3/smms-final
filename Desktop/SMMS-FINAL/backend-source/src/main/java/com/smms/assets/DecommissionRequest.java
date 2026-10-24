package com.smms.assets;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DecommissionRequest(@NotBlank @Size(max = 1000) String reason) {}
