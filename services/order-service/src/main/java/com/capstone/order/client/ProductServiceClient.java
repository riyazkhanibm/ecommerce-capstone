package com.capstone.order.client;

import com.capstone.order.dto.ProductResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class ProductServiceClient {

    private final WebClient webClient;

        public ProductServiceClient(WebClient productWebClient) {
        this.webClient = productWebClient;
    }

    public ProductResponse getProduct(Long productId) {
        try {
            return webClient.get()
                    .uri("/api/products/{id}", productId)
                    .retrieve()
                    .bodyToMono(ProductResponse.class)
                    .block();
        } catch (WebClientResponseException.NotFound e) {
            throw new IllegalArgumentException("Product not found: " + productId);
        }
    }

    // Decrements stock in product-service after an order is placed
    public void decrementStock(Long productId, int quantity) {
        webClient.patch()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/products/{id}/stock")
                        .queryParam("delta", -quantity)
                        .build(productId))
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}
