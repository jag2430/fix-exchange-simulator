package com.example.exchange.engine;

import com.example.exchange.model.Order;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

@Slf4j
public class OrderBook {

  @Getter
  private final String symbol;

  // Bids sorted by price descending (highest first)
  private final NavigableMap<BigDecimal, LinkedList<Order>> bids =
      new ConcurrentSkipListMap<>(Comparator.reverseOrder());

  // Asks sorted by price ascending (lowest first)
  private final NavigableMap<BigDecimal, LinkedList<Order>> asks =
      new ConcurrentSkipListMap<>();

  // Index by exchange order ID (ORD1, ORD2, etc.)
  private final Map<String, Order> orderIndex = new HashMap<>();

  // Index by client order ID (clOrdId)
  private final Map<String, Order> clOrdIdIndex = new HashMap<>();

  public OrderBook(String symbol) {
    this.symbol = symbol;
  }

  public synchronized void addOrder(Order order) {
    NavigableMap<BigDecimal, LinkedList<Order>> book =
        order.getSide() == Order.Side.BUY ? bids : asks;

    book.computeIfAbsent(order.getPrice(), k -> new LinkedList<>())
        .addLast(order);

    orderIndex.put(order.getOrderId(), order);
    clOrdIdIndex.put(order.getClOrdId(), order);

    log.debug("Added order to book: {} {} {} @ {} (orderId={}, clOrdId={})",
        order.getSide(), order.getRemainingQuantity(),
        order.getSymbol(), order.getPrice(),
        order.getOrderId(), order.getClOrdId());
  }

  public synchronized Order removeOrder(String orderId) {
    Order order = orderIndex.remove(orderId);
    if (order != null) {
      clOrdIdIndex.remove(order.getClOrdId());
      removeFromBook(order);
    }
    return order;
  }

  public synchronized Order removeOrderByClOrdId(String clOrdId) {
    Order order = clOrdIdIndex.remove(clOrdId);
    if (order != null) {
      orderIndex.remove(order.getOrderId());
      removeFromBook(order);
    }
    return order;
  }

  private void removeFromBook(Order order) {
    NavigableMap<BigDecimal, LinkedList<Order>> book =
        order.getSide() == Order.Side.BUY ? bids : asks;

    LinkedList<Order> priceLevel = book.get(order.getPrice());
    if (priceLevel != null) {
      priceLevel.remove(order);
      if (priceLevel.isEmpty()) {
        book.remove(order.getPrice());
      }
    }
  }

  public synchronized Order getBestBid() {
    if (bids.isEmpty()) return null;
    LinkedList<Order> topLevel = bids.firstEntry().getValue();
    return topLevel.isEmpty() ? null : topLevel.getFirst();
  }

  public synchronized Order getBestAsk() {
    if (asks.isEmpty()) return null;
    LinkedList<Order> topLevel = asks.firstEntry().getValue();
    return topLevel.isEmpty() ? null : topLevel.getFirst();
  }

  public synchronized BigDecimal getBestBidPrice() {
    Order bestBid = getBestBid();
    return bestBid != null ? bestBid.getPrice() : null;
  }

  public synchronized BigDecimal getBestAskPrice() {
    Order bestAsk = getBestAsk();
    return bestAsk != null ? bestAsk.getPrice() : null;
  }

  public synchronized List<Order> getBids(int depth) {
    return getOrders(bids, depth);
  }

  public synchronized List<Order> getAsks(int depth) {
    return getOrders(asks, depth);
  }

  private List<Order> getOrders(NavigableMap<BigDecimal, LinkedList<Order>> book, int depth) {
    List<Order> orders = new ArrayList<>();
    int count = 0;
    for (LinkedList<Order> level : book.values()) {
      for (Order order : level) {
        orders.add(order);
        if (++count >= depth) return orders;
      }
    }
    return orders;
  }

  public synchronized Order getOrder(String orderId) {
    return orderIndex.get(orderId);
  }

  public synchronized Order getOrderByClOrdId(String clOrdId) {
    return clOrdIdIndex.get(clOrdId);
  }
}