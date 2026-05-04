package com.specter.embedder.controller;

import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok", "1.0", ContractConstants.CONTRACT_VERSION);
    }
}
