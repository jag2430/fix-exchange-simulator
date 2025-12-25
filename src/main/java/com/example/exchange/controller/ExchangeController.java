package com.example.exchange.controller;

import com.example.exchange.engine.MatchingEngine;
import com.example.exchange.engine.OrderBook;
import com.example.exchange.model.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/exchange")
@RequiredArgsConstructor
public class ExchangeController {
    
    private final MatchingEngine matchingEngine;
    
    @GetMapping("/orderbook/{symbol}")
    public Map<String, Object> getOrderBook(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "10") int depth) {
        
        OrderBook book = matchingEngine.getOrderBook(symbol);
        
        Map<String, Object> response = new HashMap<>();
        response.put("symbol", symbol);
        
        if (book != null) {
            response.put("bids", formatOrders(book.getBids(depth)));
            response.put("asks", formatOrders(book.getAsks(depth)));
            response.put("bestBid", book.getBestBidPrice());
            response.put("bestAsk", book.getBestAskPrice());
        } else {
            response.put("bids", List.of());
            response.put("asks", List.of());
        }
        
        return response;
    }
    
    @GetMapping("/symbols")
    public List<String> getSymbols() {
        return matchingEngine.getAllOrderBooks().keySet().stream().toList();
    }
    
    private List<Map<String, Object>> formatOrders(List<Order> orders) {
        return orders.stream()
            .map(o -> {
                Map<String, Object> map = new HashMap<>();
                map.put("orderId", o.getOrderId());
                map.put("price", o.getPrice());
                map.put("quantity", o.getRemainingQuantity());
                map.put("side", o.getSide().name());
                return map;
            })
            .toList();
    }
}
