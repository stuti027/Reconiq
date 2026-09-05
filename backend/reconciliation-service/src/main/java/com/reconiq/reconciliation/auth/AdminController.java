package com.reconiq.reconciliation.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @GetMapping("/access")
    public Map<String, String> adminAccess() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("message", "Admin access granted");
        return body;
    }
}
