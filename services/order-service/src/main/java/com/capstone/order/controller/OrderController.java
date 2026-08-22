package com.capstone.order.controller;

import com.capstone.order.client.ProductServiceClient;
import com.capstone.order.dto.CreateOrderRequest;
import com.capstone.order.dto.OrderItemRequest;
import com.capstone.order.dto.ProductResponse;
import com.capstone.order.model.Order;
import com.capstone.order.model.OrderItem;
import com.capstone.order.repository.OrderRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "*")
public class OrderController {

    private final OrderRepository orderRepository;
    private final ProductServiceClient productServiceClient;

    public OrderController(OrderRepository orderRepository, ProductServiceClient productServiceClient) {
        this.orderRepository = orderRepository;
        this.productServiceClient = productServiceClient;
    }

    @GetMapping
    public List<Order> getAll() {
        return orderRepository.findAll();
    }

    @GetMapping("/{id}")
    public Order getById(@PathVariable Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));
    }

    // This endpoint is the "riskiest piece" in the write-up: it calls out to
    // product-service synchronously, validates stock, and only then persists
    // the order and decrements stock. See docs/ARCHITECTURE.md for the
    // trade-off discussion (synchronous call vs. event-driven saga).
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Order createOrder(@Valid @RequestBody CreateOrderRequest request) {
        Order order = new Order();
        order.setCustomerName(request.getCustomerName());

        BigDecimal total = BigDecimal.ZERO;

        for (OrderItemRequest itemRequest : request.getItems()) {
            ProductResponse product = productServiceClient.getProduct(itemRequest.getProductId());

            if (product.getStockQuantity() < itemRequest.getQuantity()) {
                throw new IllegalStateException(
                        "Insufficient stock for product " + product.getName()
                                + " (requested " + itemRequest.getQuantity()
                                + ", available " + product.getStockQuantity() + ")");
            }

            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductId(product.getId());
            item.setProductName(product.getName());
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(product.getPrice());
            order.getItems().add(item);

            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(itemRequest.getQuantity())));
        }

        order.setTotalAmount(total);
        Order saved = orderRepository.save(order);

        // Decrement stock only after the order is successfully persisted.
        for (OrderItem item : saved.getItems()) {
            productServiceClient.decrementStock(item.getProductId(), item.getQuantity());
        }

        saved.setStatus("CONFIRMED");
        return orderRepository.save(saved);
    }
}
