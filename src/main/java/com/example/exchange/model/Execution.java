package com.example.exchange.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class Execution {
  private String execId;
  private String orderId;
  private String clOrdId;
  private String origClOrdId;  // For cancel/replace responses
  private String symbol;
  private Order.Side side;
  private BigDecimal execPrice;
  private long execQuantity;
  private long leavesQty;
  private long cumQty;
  private ExecType execType;
  private Order.OrderStatus orderStatus;
  private LocalDateTime execTime;

  public enum ExecType {
    NEW('0'),
    PARTIAL_FILL('1'),
    FILL('2'),
    CANCELLED('4'),
    REPLACED('5'),
    REJECTED('8');

    private final char fixValue;

    ExecType(char fixValue) {
      this.fixValue = fixValue;
    }

    public char getFixValue() {
      return fixValue;
    }
  }
}