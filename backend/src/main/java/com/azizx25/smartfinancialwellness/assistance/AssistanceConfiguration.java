package com.azizx25.smartfinancialwellness.assistance;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssistanceProperties.class)
public class AssistanceConfiguration {
    @Bean
    AssistanceClient assistanceClient(AssistanceProperties properties) {
        var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout()).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return new AssistanceClient(RestClient.builder().baseUrl(properties.baseUrl().toString())
                .requestFactory(factory).build(), properties);
    }
}
