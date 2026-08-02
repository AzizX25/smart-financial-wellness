package com.azizx25.smartfinancialwellness.bank.infrastructure.mockbank;

import java.net.http.HttpClient;

import com.azizx25.smartfinancialwellness.bank.ReadOnlyBankDataPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MockBankClientProperties.class)
public class MockBankClientConfiguration {

    @Bean
    ReadOnlyBankDataPort mockBankReadOnlyBankDataPort(
            JsonMapper applicationJsonMapper,
            MockBankClientProperties properties) {
        JsonMapper tolerantJsonMapper = tolerantJsonMapper(applicationJsonMapper);
        RestClient restClient = configureBuilder(
                RestClient.builder(),
                properties,
                tolerantJsonMapper).build();
        return new MockBankHttpClient(restClient, tolerantJsonMapper);
    }

    static RestClient.Builder configureBuilder(
            RestClient.Builder builder,
            MockBankClientProperties properties,
            JsonMapper tolerantJsonMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(tolerantJsonMapper)));
    }

    static JsonMapper tolerantJsonMapper(JsonMapper applicationJsonMapper) {
        return applicationJsonMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
