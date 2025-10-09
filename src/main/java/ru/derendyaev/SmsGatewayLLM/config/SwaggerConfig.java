package ru.derendyaev.SmsGatewayLLM.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI smsGatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SMS Gateway LLM API")
                        .description("""
                                API для взаимодействия с GigaChat и другими сервисами LLM.
                                Содержит методы для отправки сообщений, получения токенов, системных промптов и т.д.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Derendyaev Project")
                                .email("support@sms-gateway.derendyaev.ru")
                                .url("https://sms-gateway.derendyaev.ru"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")));
    }
}

