package com.example.exchange.engine;

import com.example.exchange.model.Execution;
import com.example.exchange.model.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class MatchingEngine {

  private final Map<String, OrderBook> orderBooks = new ConcurrentHashMap<>();
  private final AtomicLong orderIdGenerator = new AtomicLong(1);
  private final AtomicLong execIdGenerator = new AtomicLong(1);

  public List<Execution> submitOrder(Order order) {
    List<Execution> executions = new ArrayList<>();

    // Assign order ID
    order.setOrderId(generateOrderId());
    order.setCreatedTime(LocalDateTime.now());
    order.setRemainingQuantity(order.getQuantity());
    order.setFilledQuantity(0);
    order.setStatus(Order.OrderStatus.NEW);

    // Get or create order book
    OrderBook book = orderBooks.computeIfAbsent(
        order.getSymbol(),
        OrderBook::new
    );

    // Generate NEW execution report
    executions.add(createExecution(order, Execution.ExecType.NEW,
        BigDecimal.ZERO, 0));

    // Try to match
    if (order.getOrderType() == Order.OrderType.LIMIT) {
      executions.addAll(matchLimitOrder(order, book));
    } else {
      executions.addAll(matchMarketOrder(order, book));
    }

    // If order still has remaining quantity, add to book
    if (order.getRemainingQuantity() > 0 &&
        order.getOrderType() == Order.OrderType.LIMIT) {
      book.addOrder(order);
    }

    return executions;
  }

  private List<Execution> matchLimitOrder(Order order, OrderBook book) {
    List<Execution> executions = new ArrayList<>();

    while (order.getRemainingQuantity() > 0) {
      Order counterOrder = order.getSide() == Order.Side.BUY
          ? book.getBestAsk()
          : book.getBestBid();

      if (counterOrder == null) break;

      // Check price compatibility
      boolean priceMatch = order.getSide() == Order.Side.BUY
          ? order.getPrice().compareTo(counterOrder.getPrice()) >= 0
          : order.getPrice().compareTo(counterOrder.getPrice()) <= 0;

      if (!priceMatch) break;

      executions.addAll(executeMatch(order, counterOrder, book));
    }

    return executions;
  }

  private List<Execution> matchMarketOrder(Order order, OrderBook book) {
    List<Execution> executions = new ArrayList<>();

    while (order.getRemainingQuantity() > 0) {
      Order counterOrder = order.getSide() == Order.Side.BUY
          ? book.getBestAsk()
          : book.getBestBid();

      if (counterOrder == null) {
        // No liquidity - reject remaining
        order.setStatus(Order.OrderStatus.REJECTED);
        executions.add(createExecution(order, Execution.ExecType.REJECTED,
            BigDecimal.ZERO, 0));
        break;
      }

      executions.addAll(executeMatch(order, counterOrder, book));
    }

    return executions;
  }

  private List<Execution> executeMatch(Order aggressor, Order passive, OrderBook book) {
    List<Execution> executions = new ArrayList<>();

    long matchQty = Math.min(aggressor.getRemainingQuantity(),
        passive.getRemainingQuantity());
    BigDecimal matchPrice = passive.getPrice(); // Price improvement for aggressor

    // Update aggressor
    aggressor.setFilledQuantity(aggressor.getFilledQuantity() + matchQty);
    aggressor.setRemainingQuantity(aggressor.getRemainingQuantity() - matchQty);

    // Update passive
    passive.setFilledQuantity(passive.getFilledQuantity() + matchQty);
    passive.setRemainingQuantity(passive.getRemainingQuantity() - matchQty);

    // Determine execution types
    Execution.ExecType aggressorExecType = aggressor.getRemainingQuantity() == 0
        ? Execution.ExecType.FILL : Execution.ExecType.PARTIAL_FILL;
    Execution.ExecType passiveExecType = passive.getRemainingQuantity() == 0
        ? Execution.ExecType.FILL : Execution.ExecType.PARTIAL_FILL;

    // Update order statuses
    aggressor.setStatus(aggressor.getRemainingQuantity() == 0
        ? Order.OrderStatus.FILLED : Order.OrderStatus.PARTIALLY_FILLED);
    passive.setStatus(passive.getRemainingQuantity() == 0
        ? Order.OrderStatus.FILLED : Order.OrderStatus.PARTIALLY_FILLED);

    // Create execution reports
    executions.add(createExecution(aggressor, aggressorExecType, matchPrice, matchQty));
    executions.add(createExecution(passive, passiveExecType, matchPrice, matchQty));

    // Remove filled passive order from book
    if (passive.getRemainingQuantity() == 0) {
      book.removeOrder(passive.getOrderId());
    }

    log.info("MATCH: {} {} @ {} | Aggressor: {} Passive: {}",
        matchQty, aggressor.getSymbol(), matchPrice,
        aggressor.getOrderId(), passive.getOrderId());

    return executions;
  }

  /**
   * Cancel an order by its original client order ID (origClOrdId).
   *
   * @param symbol The symbol of the order
   * @param origClOrdId The original client order ID to cancel
   * @param clOrdId The new client order ID for this cancel request
   * @return Execution report for the cancel
   */
  public Execution cancelOrder(String symbol, String origClOrdId, String clOrdId) {
    OrderBook book = orderBooks.get(symbol);
    if (book == null) {
      log.warn("Cancel rejected - unknown symbol: {}", symbol);
      return createRejectedCancel(origClOrdId, clOrdId, symbol, "Unknown symbol");
    }

    // Look up by client order ID (origClOrdId)
    Order order = book.removeOrderByClOrdId(origClOrdId);
    if (order == null) {
      log.warn("Cancel rejected - order not found: clOrdId={}", origClOrdId);
      return createRejectedCancel(origClOrdId, clOrdId, symbol, "Order not found");
    }

    order.setStatus(Order.OrderStatus.CANCELLED);

    log.info("Order cancelled: orderId={}, clOrdId={}, symbol={}, remainingQty={}",
        order.getOrderId(), origClOrdId, symbol, order.getRemainingQuantity());

    return Execution.builder()
        .execId(generateExecId())
        .orderId(order.getOrderId())
        .clOrdId(clOrdId)
        .origClOrdId(origClOrdId)
        .symbol(order.getSymbol())
        .side(order.getSide())
        .execPrice(BigDecimal.ZERO)
        .execQuantity(0)
        .leavesQty(0)
        .cumQty(order.getFilledQuantity())
        .execType(Execution.ExecType.CANCELLED)
        .orderStatus(Order.OrderStatus.CANCELLED)
        .execTime(LocalDateTime.now())
        .build();
  }

  /**
   * Amend (Cancel/Replace) an order.
   *
   * @param symbol The symbol of the order
   * @param origClOrdId The original client order ID to amend
   * @param clOrdId The new client order ID for the amended order
   * @param newQuantity The new total quantity (optional, null to keep existing)
   * @param newPrice The new price (optional, null to keep existing)
   * @return List of execution reports (REPLACED for the old order, potentially NEW and fills for new)
   */
  public List<Execution> amendOrder(String symbol, String origClOrdId, String clOrdId,
                                    Long newQuantity, BigDecimal newPrice) {
    List<Execution> executions = new ArrayList<>();

    OrderBook book = orderBooks.get(symbol);
    if (book == null) {
      log.warn("Amend rejected - unknown symbol: {}", symbol);
      executions.add(createRejectedAmend(origClOrdId, clOrdId, symbol, "Unknown symbol"));
      return executions;
    }

    // Find the original order (don't remove yet)
    Order originalOrder = book.getOrderByClOrdId(origClOrdId);
    if (originalOrder == null) {
      log.warn("Amend rejected - order not found: clOrdId={}", origClOrdId);
      executions.add(createRejectedAmend(origClOrdId, clOrdId, symbol, "Order not found"));
      return executions;
    }

    // Validate new quantity if provided
    long effectiveNewQty = newQuantity != null ? newQuantity : originalOrder.getQuantity();
    if (effectiveNewQty < originalOrder.getFilledQuantity()) {
      log.warn("Amend rejected - new quantity {} less than filled quantity {}",
          effectiveNewQty, originalOrder.getFilledQuantity());
      executions.add(createRejectedAmend(origClOrdId, clOrdId, symbol,
          "New quantity less than filled quantity"));
      return executions;
    }

    // Remove the original order from the book
    book.removeOrderByClOrdId(origClOrdId);

    // Create the amended order
    BigDecimal effectivePrice = newPrice != null ? newPrice : originalOrder.getPrice();
    long newRemainingQty = effectiveNewQty - originalOrder.getFilledQuantity();

    Order amendedOrder = Order.builder()
        .orderId(generateOrderId())
        .clOrdId(clOrdId)
        .symbol(symbol)
        .side(originalOrder.getSide())
        .orderType(originalOrder.getOrderType())
        .price(effectivePrice)
        .quantity(effectiveNewQty)
        .filledQuantity(originalOrder.getFilledQuantity())
        .remainingQuantity(newRemainingQty)
        .status(Order.OrderStatus.NEW)
        .senderCompId(originalOrder.getSenderCompId())
        .targetCompId(originalOrder.getTargetCompId())
        .createdTime(LocalDateTime.now())
        .build();

    // Generate REPLACED execution report
    executions.add(Execution.builder()
        .execId(generateExecId())
        .orderId(amendedOrder.getOrderId())
        .clOrdId(clOrdId)
        .origClOrdId(origClOrdId)
        .symbol(symbol)
        .side(amendedOrder.getSide())
        .execPrice(effectivePrice)
        .execQuantity(0)
        .leavesQty(newRemainingQty)
        .cumQty(amendedOrder.getFilledQuantity())
        .execType(Execution.ExecType.REPLACED)
        .orderStatus(Order.OrderStatus.NEW)
        .execTime(LocalDateTime.now())
        .build());

    log.info("Order amended: origClOrdId={}, newClOrdId={}, newQty={}, newPrice={}",
        origClOrdId, clOrdId, effectiveNewQty, effectivePrice);

    // Try to match the amended order
    if (amendedOrder.getRemainingQuantity() > 0) {
      if (amendedOrder.getOrderType() == Order.OrderType.LIMIT) {
        executions.addAll(matchLimitOrder(amendedOrder, book));
      } else {
        executions.addAll(matchMarketOrder(amendedOrder, book));
      }

      // If still has remaining quantity, add to book
      if (amendedOrder.getRemainingQuantity() > 0 &&
          amendedOrder.getOrderType() == Order.OrderType.LIMIT) {
        book.addOrder(amendedOrder);
      }
    }

    return executions;
  }

  private Execution createExecution(Order order, Execution.ExecType execType,
                                    BigDecimal price, long quantity) {
    return Execution.builder()
        .execId(generateExecId())
        .orderId(order.getOrderId())
        .clOrdId(order.getClOrdId())
        .symbol(order.getSymbol())
        .side(order.getSide())
        .execPrice(price)
        .execQuantity(quantity)
        .leavesQty(order.getRemainingQuantity())
        .cumQty(order.getFilledQuantity())
        .execType(execType)
        .orderStatus(order.getStatus())
        .execTime(LocalDateTime.now())
        .build();
  }

  private Execution createRejectedCancel(String origClOrdId, String clOrdId,
                                         String symbol, String reason) {
    log.warn("Cancel rejected for origClOrdId={}: {}", origClOrdId, reason);
    return Execution.builder()
        .execId(generateExecId())
        .orderId(origClOrdId)
        .clOrdId(clOrdId)
        .symbol(symbol)
        .execType(Execution.ExecType.REJECTED)
        .orderStatus(Order.OrderStatus.REJECTED)
        .execTime(LocalDateTime.now())
        .build();
  }

  private Execution createRejectedAmend(String origClOrdId, String clOrdId,
                                        String symbol, String reason) {
    log.warn("Amend rejected for origClOrdId={}: {}", origClOrdId, reason);
    return Execution.builder()
        .execId(generateExecId())
        .orderId(origClOrdId)
        .clOrdId(clOrdId)
        .origClOrdId(origClOrdId)
        .symbol(symbol)
        .execType(Execution.ExecType.REJECTED)
        .orderStatus(Order.OrderStatus.REJECTED)
        .execTime(LocalDateTime.now())
        .build();
  }

  private String generateOrderId() {
    return "ORD" + orderIdGenerator.getAndIncrement();
  }

  private String generateExecId() {
    return "EXEC" + execIdGenerator.getAndIncrement();
  }

  public OrderBook getOrderBook(String symbol) {
    return orderBooks.get(symbol);
  }

  public Map<String, OrderBook> getAllOrderBooks() {
    return Collections.unmodifiableMap(orderBooks);
  }
}