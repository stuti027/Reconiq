package com.reconiq.reporting.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(ApiExceptionHandlerTest.ThrowingController.class)
@Import({ApiExceptionHandler.class, ApiExceptionHandlerTest.ThrowingController.class})
class ApiExceptionHandlerTest {

    @RestController
    static class ThrowingController {
        @GetMapping("/test/bad")
        String bad() {
            throw new BadRequestException("Unknown sort column: amountt");
        }

        @GetMapping("/test/missing")
        String missing() {
            throw new NotFoundException("No transaction with event_id evt_x");
        }

        @GetMapping("/test/number")
        String number(@RequestParam int page) {
            return "ok";
        }

        @GetMapping("/test/boom")
        String boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void badRequestIs400WithMessage() throws Exception {
        mockMvc.perform(get("/test/bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown sort column: amountt"));
    }

    @Test
    void notFoundIs404() throws Exception {
        mockMvc.perform(get("/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No transaction with event_id evt_x"));
    }

    @Test
    void wrongParameterTypeIs400() throws Exception {
        mockMvc.perform(get("/test/number").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for parameter: page"));
    }

    @Test
    void unknownPathIs404InTheSameShape() throws Exception {
        mockMvc.perform(get("/api/reporting/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void unexpectedErrorHidesInternals() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal error"));
    }
}
