package com.example.exchange.fix;

import com.example.exchange.engine.MatchingEngine;
import com.example.exchange.model.Execution;
import com.example.exchange.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeFixApplication implements Application {

  private final MatchingEngine matchingEngine;

  @Override
  public void onCreate(SessionID sessionId) {
    log.info("Session created: {}", sessionId);
  }

  @Override
  public void onLogon(SessionID sessionId) {
    log.info("Client logged on: {}", sessionId);
  }

  @Override
  public void onLogout(SessionID sessionId) {
    log.info("Client logged out: {}", sessionId);
  }

  @Override
  public void toAdmin(Message message, SessionID sessionId) {
    // Can modify outgoing admin messages here
  }

  @Override
  public void fromAdmin(Message message, SessionID sessionId)
      throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
    // Handle incoming admin messages
  }

  @Override
  public void toApp(Message message, SessionID sessionId) throws DoNotSend {
    log.debug("Sending message: {}", message);
  }

  @Override
  public void fromApp(Message message, SessionID sessionId)
      throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {

    log.debug("Received message: {}", message);

    try {
      String msgType = message.getHeader().getString(MsgType.FIELD);

      switch (msgType) {
        case MsgType.ORDER_SINGLE -> handleNewOrder((NewOrderSingle) message, sessionId);
        case MsgType.ORDER_CANCEL_REQUEST -> handleCancelRequest((OrderCancelRequest) message, sessionId);
        case MsgType.ORDER_CANCEL_REPLACE_REQUEST -> handleCancelReplaceRequest((OrderCancelReplaceRequest) message, sessionId);
        default -> log.warn("Unsupported message type: {}", msgType);
      }
    } catch (Exception e) {
      log.error("Error processing message", e);
    }
  }

  private void handleNewOrder(NewOrderSingle message, SessionID sessionId) throws FieldNotFound {
    Order order = Order.builder()
        .clOrdId(message.getClOrdID().getValue())
        .symbol(message.getSymbol().getValue())
        .side(Order.Side.fromFixValue(message.getSide().getValue()))
        .orderType(Order.OrderType.fromFixValue(message.getOrdType().getValue()))
        .quantity((long) message.getOrderQty().getValue())
        .price(message.isSetPrice()
            ? BigDecimal.valueOf(message.getPrice().getValue())
            : BigDecimal.ZERO)
        .senderCompId(sessionId.getTargetCompID())
        .targetCompId(sessionId.getSenderCompID())
        .build();

    log.info("Received new order: {} {} {} @ {}",
        order.getSide(), order.getQuantity(),
        order.getSymbol(), order.getPrice());

    List<Execution> executions = matchingEngine.submitOrder(order);

    for (Execution exec : executions) {
      sendExecutionReport(exec, sessionId);
    }
  }

  private void handleCancelRequest(OrderCancelRequest message, SessionID sessionId)
      throws FieldNotFound {

    String origClOrdId = message.getOrigClOrdID().getValue();
    String clOrdId = message.getClOrdID().getValue();
    String symbol = message.getSymbol().getValue();

    log.info("Received cancel request for order: {}", origClOrdId);

    Execution exec = matchingEngine.cancelOrder(symbol, origClOrdId, clOrdId);
    sendExecutionReport(exec, sessionId);
  }

  private void handleCancelReplaceRequest(OrderCancelReplaceRequest message, SessionID sessionId)
      throws FieldNotFound {

    String origClOrdId = message.getOrigClOrdID().getValue();
    String clOrdId = message.getClOrdID().getValue();
    String symbol = message.getSymbol().getValue();

    // Get new quantity if provided
    Long newQuantity = null;
    if (message.isSetOrderQty()) {
      newQuantity = (long) message.getOrderQty().getValue();
    }

    // Get new price if provided
    BigDecimal newPrice = null;
    if (message.isSetPrice()) {
      newPrice = BigDecimal.valueOf(message.getPrice().getValue());
    }

    log.info("Received amend request for order: {} newQty={} newPrice={}",
        origClOrdId, newQuantity, newPrice);

    List<Execution> executions = matchingEngine.amendOrder(symbol, origClOrdId, clOrdId,
        newQuantity, newPrice);

    for (Execution exec : executions) {
      sendExecutionReport(exec, sessionId);
    }
  }

  private void sendExecutionReport(Execution exec, SessionID sessionId) {
    try {
      ExecutionReport report = new ExecutionReport(
          new OrderID(exec.getOrderId()),
          new ExecID(exec.getExecId()),
          new ExecType(exec.getExecType().getFixValue()),
          new OrdStatus(exec.getOrderStatus().getFixValue()),
          new Side(exec.getSide() != null ? exec.getSide().getFixValue() : Side.BUY),
          new LeavesQty(exec.getLeavesQty()),
          new CumQty(exec.getCumQty()),
          new AvgPx(exec.getExecPrice() != null ? exec.getExecPrice().doubleValue() : 0)
      );

      report.set(new ClOrdID(exec.getClOrdId()));
      report.set(new Symbol(exec.getSymbol()));

      // Set OrigClOrdID for cancel/replace responses
      if (exec.getOrigClOrdId() != null) {
        report.set(new OrigClOrdID(exec.getOrigClOrdId()));
      }

      if (exec.getExecQuantity() > 0) {
        report.set(new LastQty(exec.getExecQuantity()));
        report.set(new LastPx(exec.getExecPrice().doubleValue()));
      }

      report.set(new TransactTime(exec.getExecTime()));

      Session.sendToTarget(report, sessionId);

      log.info("Sent execution report: {} {} {} - Status: {}",
          exec.getExecType(), exec.getExecQuantity(),
          exec.getSymbol(), exec.getOrderStatus());

    } catch (SessionNotFound e) {
      log.error("Failed to send execution report", e);
    }
  }
}