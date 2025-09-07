package ru.derendyaev.SmsGatewayLLM.restUtils;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;

@Component
public class WebClientFactory {
    private static final int TIMEOUT = 1000;

    public static WebClient createWebClient(String baseUrl) {
        SslContext sslContext = null;
        try {
            sslContext = SslContextBuilder
                    .forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }

        SslContext finalSslContext = sslContext;
        HttpClient httpClient = HttpClient.create().secure(t -> t.sslContext(finalSslContext));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(clientCodecConfigurer -> {
                        clientCodecConfigurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder());
                        clientCodecConfigurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder());
                })
                .build();
    }
}