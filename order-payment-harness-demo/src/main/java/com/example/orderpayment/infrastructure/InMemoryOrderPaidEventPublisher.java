package com.example.orderpayment.infrastructure;

import com.example.orderpayment.application.port.OrderPaidEventPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

@Component
public class InMemoryOrderPaidEventPublisher implements OrderPaidEventPublisher {

    private final List<OrderPaidEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(OrderPaidEvent event) {
        events.add(event);
    }

    public List<OrderPaidEvent> events() {
        return List.copyOf(events);
    }

    public void clear() {
        events.clear();
    }
}
