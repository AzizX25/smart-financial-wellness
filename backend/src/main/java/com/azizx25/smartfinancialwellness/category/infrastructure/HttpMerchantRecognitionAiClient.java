package com.azizx25.smartfinancialwellness.category.infrastructure;

import java.util.List;

import com.azizx25.smartfinancialwellness.category.infrastructure.MerchantAiExceptions.InvalidResponse;
import com.azizx25.smartfinancialwellness.category.infrastructure.MerchantAiExceptions.Unavailable;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class HttpMerchantRecognitionAiClient implements MerchantRecognitionAiClient {
    private final RestClient client;

    HttpMerchantRecognitionAiClient(RestClient client) { this.client = client; }

    @Override
    public List<Prediction> predict(List<RequestItem> items) {
        try {
            BatchResponse response = client.post().uri("/api/v1/merchant-recognition/predict")
                    .body(new BatchRequest(items)).retrieve().body(BatchResponse.class);
            if (response == null || response.items() == null) throw new InvalidResponse("Empty merchant response");
            return response.items();
        } catch (ResourceAccessException exception) {
            throw new Unavailable(exception);
        } catch (InvalidResponse exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new InvalidResponse(exception);
        }
    }

    private record BatchRequest(List<RequestItem> items) { }
    private record BatchResponse(List<Prediction> items) { }
}
