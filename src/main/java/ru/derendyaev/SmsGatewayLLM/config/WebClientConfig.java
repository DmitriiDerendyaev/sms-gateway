package ru.derendyaev.SmsGatewayLLM.config;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;

@Configuration
public class WebClientConfig {

    @Value("${app.values.api.giga-chat.base-url.auth}")
    private String gigaAuthUrl;

    @Value("${app.values.api.giga-chat.base-url.chat}")
    private String gigaChatUrl;

    @Value("${app.values.api.sms-gateway.base-url}")
    private String smsGatewayUrl;

    @Bean("gigaAuthWebClient")
    public WebClient gigaAuthWebClient() throws SSLException {
        return createWebClient(gigaAuthUrl);
    }

    @Bean("gigaChatWebClient")
    public WebClient gigaChatWebClient() throws SSLException {
        return createWebClient(gigaChatUrl);
    }

    @Bean("smsWebClient")
    public WebClient smsWebClient() throws SSLException {
        return createWebClient(smsGatewayUrl);
    }

    private WebClient createWebClient(String baseUrl) throws SSLException {
        SslContext sslContext = SslContextBuilder
                .forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE) // доверяем всем сертификатам
                .build();

        HttpClient httpClient = HttpClient.create()
                .secure(t -> t.sslContext(sslContext));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
