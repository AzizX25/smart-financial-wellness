package com.azizx25.smartfinancialwellness.category.infrastructure;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MerchantAiClientProperties.class)
public class MerchantAiClientConfiguration {

    @Bean
    MerchantRecognitionAiClient merchantRecognitionAiClient(MerchantAiClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return new HttpMerchantRecognitionAiClient(RestClient.builder()
                .baseUrl(properties.baseUrl().toString()).requestFactory(factory).build());
    }
}
