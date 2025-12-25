package com.example.exchange.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class Order {
    private String orderId;
    private String clOrdId;
    private String symbol;
    private Side side;
    private OrderType orderType;
    private BigDecimal price;
    private long quantity;
    private long filledQuantity;
    private long remainingQuantity;
    private OrderStatus status;
    private String senderCompId;
    private String targetCompId;
    private LocalDateTime createdTime;
    
    public enum Side {
        BUY('1'), SELL('2');
        
        private final char fixValue;
        
        Side(char fixValue) {
            this.fixValue = fixValue;
        }
        
        public char getFixValue() {
            return fixValue;
        }
        
        public static Side fromFixValue(char value) {
            return value == '1' ? BUY : SELL;
        }
    }
    
    public enum OrderType {
        MARKET('1'), LIMIT('2');
        
        private final char fixValue;
        
        OrderType(char fixValue) {
            this.fixValue = fixValue;
        }
        
        public char getFixValue() {
            return fixValue;
        }
        
        public static OrderType fromFixValue(char value) {
            return value == '1' ? MARKET : LIMIT;
        }
    }
    
    public enum OrderStatus {
        NEW('0'), 
        PARTIALLY_FILLED('1'), 
        FILLED('2'), 
        CANCELLED('4'),
        REJECTED('8');
        
        private final char fixValue;
        
        OrderStatus(char fixValue) {
            this.fixValue = fixValue;
        }
        
        public char getFixValue() {
            return fixValue;
        }
    }
}
