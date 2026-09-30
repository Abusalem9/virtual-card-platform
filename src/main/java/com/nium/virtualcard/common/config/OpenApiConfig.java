package com.nium.virtualcard.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI virtualCardOpenAPI() {
        return new OpenAPI().info(new Info().title("Virtual Card Issuance Platform API")
                .description("""
                        REST API for virtual card issuance, top-ups, spending,
                        transaction history, idempotency, card lifecycle,
                        and financial operations.
                        """).version("1.0.0").contact(new Contact().name("Abusalem M")));
    }
}