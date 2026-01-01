# FIX Exchange Simulator

A Spring Boot application that simulates a financial exchange using QuickFIX/J for FIX protocol communication, featuring a price-time priority matching engine and built-in liquidity provider for realistic order execution.

## Overview

The FIX Exchange Simulator provides a realistic trading venue for testing FIX-based trading applications. It implements a full order matching engine with price-time priority and includes an intelligent liquidity provider that automatically posts bid/ask quotes using real market prices from Finnhub.

## Features

### Order Matching Engine
- **Price-Time Priority**: Orders matched by best price, then arrival time
- **Continuous Matching**: Incoming orders immediately match against resting orders
- **Partial Fills**: Support for partial order execution
- **Order Book Management**: Maintains separate bid and ask books per symbol

### Liquidity Provider
- **Automatic Quote Generation**: Posts market maker quotes when first order arrives
- **Real Reference Prices**: Fetches actual prices from Finnhub API
- **Liquidity Profiles**: Spread and size based on market cap tier
- **Multi-Level Quotes**: Posts multiple price levels with increasing size

### FIX Protocol Support
- **FIX 4.4** protocol via QuickFIX/J
- **NewOrderSingle (D)**: Accept new orders
- **OrderCancelRequest (F)**: Process cancellations
- **OrderCancelReplaceRequest (G)**: Handle amendments
- **ExecutionReport (8)**: Send acknowledgments and fills

### REST API
- Order book inspection
- Liquidity provider management
- Symbol listings
- Health monitoring

## How It Works

### Order Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Order Processing Flow                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. Order Arrives                                                           │
│     ┌─────────────────────────────────────────────────────────┐             │
│     │ NewOrderSingle: BUY 100 AAPL @ LIMIT $150.10            │             │
│     └─────────────────────────────────────────────────────────┘             │
│                              │                                              │
│                              ▼                                              │
│  2. Check Liquidity                                                         │
│     ┌─────────────────────────────────────────────────────────┐             │
│     │ Is this the first order for AAPL?                       │             │
│     │   YES → Fetch price from Finnhub                        │             │
│     │       → Determine liquidity profile (MEGA_CAP)          │             │
│     │       → Post market maker quotes                        │             │
│     │   NO  → Skip, quotes already exist                      │             │
│     └─────────────────────────────────────────────────────────┘             │
│                              │                                              │
│                              ▼                                              │
│  3. Attempt Matching                                                        │
│     ┌─────────────────────────────────────────────────────────┐             │
│     │ Check order book for matchable counter-orders           │             │
│     │                                                         │             │
│     │ BUY $150.10 vs Best ASK $150.05                         │             │
│     │ $150.10 >= $150.05? YES → Match at $150.05              │             │
│     └─────────────────────────────────────────────────────────┘             │
│                              │                                              │
│                              ▼                                              │
│  4. Generate Executions                                                     │
│     ┌─────────────────────────────────────────────────────────┐             │
│     │ ExecutionReport (ExecType=NEW)    → Order acknowledged  │             │
│     │ ExecutionReport (ExecType=FILL)   → Order filled        │             │
│     └─────────────────────────────────────────────────────────┘             │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Without vs With Liquidity Provider

```
Without Liquidity Provider:          With Liquidity Provider:
┌────────────────────────┐           ┌────────────────────────┐
│ Your BUY 100 AAPL      │           │ Your BUY 100 AAPL      │
│         ↓              │           │         ↓              │
│ Order rests in book    │           │ Matches MM ask quote   │
│ (no counter-order)     │           │         ↓              │
│         ↓              │           │ FILLED @ $150.15       │
│ Nothing happens...     │           └────────────────────────┘
└────────────────────────┘
```

## Order Book Structure

The matching engine maintains a two-sided order book for each symbol:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         ORDER BOOK: AAPL                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   BIDS (Buy Orders)                    ASKS (Sell Orders)                   │
│   Sorted: HIGHEST Price First          Sorted: LOWEST Price First           │
│                                                                             │
│   ┌──────────────────────────┐         ┌──────────────────────────┐         │
│   │ Price     Qty    Time    │         │ Price     Qty    Time    │         │
│   ├──────────────────────────┤         ├──────────────────────────┤         │
│   │ $150.00   500    09:30:01│◀─ Best   $150.05   300    09:30:00 ◀─ Best 
│   │ $149.95   1000   09:30:02│    Bid  │ $150.10   800    09:30:01│   Ask   │
│   │ $149.90   750    09:30:03│         │ $150.15   500    09:30:02│         │
│   │ $149.85   2000   09:30:04│         │ $150.20   1200   09:30:03│         │
│   └──────────────────────────┘         └──────────────────────────┘         │
│                                                                             │
│   Spread = Best Ask - Best Bid = $150.05 - $150.00 = $0.05 (3.3 bps)        │
│                                                                             │
│   Data Structure: ConcurrentSkipListMap<BigDecimal, LinkedList<Order>>      │
│   - Bids: Comparator.reverseOrder() (highest first)                         │
│   - Asks: Natural order (lowest first)                                      │
│   - Within price level: FIFO queue (time priority)                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Matching Algorithm

```java
// Simplified matching logic
public List<Execution> matchOrder(Order incomingOrder) {
    List<Execution> executions = new ArrayList<>();
    
    // Get the opposite side of the book
    NavigableMap<BigDecimal, Queue<Order>> counterBook = 
        incomingOrder.isBuy() ? asks : bids;
    
    while (incomingOrder.hasRemainingQuantity()) {
        // Get best price level
        Entry<BigDecimal, Queue<Order>> bestLevel = counterBook.firstEntry();
        if (bestLevel == null) break;  // No more liquidity
        
        BigDecimal bestPrice = bestLevel.getKey();
        
        // Check if prices cross
        boolean pricesCross = incomingOrder.isBuy() 
            ? incomingOrder.getPrice().compareTo(bestPrice) >= 0
            : incomingOrder.getPrice().compareTo(bestPrice) <= 0;
        
        if (!pricesCross) break;  // No match possible
        
        // Match against orders at this price level (time priority)
        Queue<Order> ordersAtPrice = bestLevel.getValue();
        Order restingOrder = ordersAtPrice.peek();
        
        // Execute at resting order's price (price improvement for aggressor)
        int matchQty = Math.min(
            incomingOrder.getRemainingQuantity(),
            restingOrder.getRemainingQuantity()
        );
        
        executions.add(new Execution(restingOrder, matchQty, bestPrice));
        executions.add(new Execution(incomingOrder, matchQty, bestPrice));
        
        // Update quantities
        restingOrder.fill(matchQty);
        incomingOrder.fill(matchQty);
        
        // Remove fully filled orders
        if (restingOrder.isFilled()) ordersAtPrice.poll();
        if (ordersAtPrice.isEmpty()) counterBook.remove(bestPrice);
    }
    
    // Add unfilled remainder to book
    if (incomingOrder.hasRemainingQuantity() && incomingOrder.isLimit()) {
        addToBook(incomingOrder);
    }
    
    return executions;
}
```

## Liquidity Provider

### Liquidity Profiles

The liquidity provider determines spread and size based on market capitalization:

| Profile | Market Cap | Spread | Base Qty | Examples |
|---------|------------|--------|----------|----------|
| MEGA_CAP | >$500B | 1 bps | 1,000 | AAPL, MSFT, GOOGL, AMZN |
| LARGE_CAP | $50-500B | 2 bps | 500 | META, NVDA, JPM |
| MID_CAP | $10-50B | 5 bps | 200 | Most S&P 500 |
| SMALL_CAP | <$10B | 10 bps | 100 | Smaller stocks |

### Multi-Level Quote Structure

With default settings and $150.00 reference price for a MEGA_CAP stock:

| Level | Spread Offset | Bid Price | Ask Price | Quantity |
|-------|---------------|-----------|-----------|----------|
| 0 | 1 bps | $149.985 | $150.015 | 1,000 |
| 1 | 2 bps | $149.970 | $150.030 | 2,000 |
| 2 | 3 bps | $149.955 | $150.045 | 4,000 |
| 3 | 4 bps | $149.940 | $150.060 | 8,000 |
| 4 | 5 bps | $149.925 | $150.075 | 16,000 |

**Total liquidity per side: 31,000 shares**

### Spread Calculation

```
1 basis point (bps) = 0.01% = 0.0001

For MEGA_CAP with 1 bps spread at $150.00:
  Bid = $150.00 × (1 - 0.0001) = $149.985
  Ask = $150.00 × (1 + 0.0001) = $150.015

For SMALL_CAP with 10 bps spread at $50.00:
  Bid = $50.00 × (1 - 0.0010) = $49.95
  Ask = $50.00 × (1 + 0.0010) = $50.05
```

## FIX Protocol Details

### Session Configuration

```properties
# quickfix-server.cfg
[DEFAULT]
ConnectionType=acceptor
HeartBtInt=30
FileStorePath=target/data/fix
FileLogPath=target/log/fix
StartTime=00:00:00
EndTime=00:00:00
UseDataDictionary=Y
DataDictionary=FIX44.xml

[SESSION]
BeginString=FIX.4.4
SenderCompID=EXEC
TargetCompID=BANZAI
SocketAcceptPort=9876
```

### Supported Messages

#### Incoming (Client → Exchange)

| Message | Type | Key Fields | Action |
|---------|------|------------|--------|
| NewOrderSingle | D | ClOrdID, Symbol, Side, OrderQty, OrdType, Price | Submit to matching engine |
| OrderCancelRequest | F | OrigClOrdID, ClOrdID, Symbol, Side | Remove from order book |
| OrderCancelReplaceRequest | G | OrigClOrdID, ClOrdID, OrderQty, Price | Cancel and replace |

#### Outgoing (Exchange → Client)

| Message | Type | When Sent | Key Fields |
|---------|------|-----------|------------|
| ExecutionReport | 8 | Order ack, fill, cancel, reject | ExecID, ExecType, OrdStatus, LastQty, LastPx, CumQty, LeavesQty, AvgPx |

### Execution Types

| ExecType | Code | Description |
|----------|------|-------------|
| NEW | 0 | Order accepted |
| PARTIAL_FILL | 1 | Partially executed |
| FILL | 2 | Fully executed |
| CANCELLED | 4 | Order cancelled |
| REPLACED | 5 | Order amended |
| REJECTED | 8 | Order rejected |

## Prerequisites

- Java 17+
- Maven 3.6+
- Finnhub API key (free at https://finnhub.io/register)

## Quick Start

### 1. Get Finnhub API Key

Sign up for free at https://finnhub.io/register

### 2. Set Environment Variable

```bash
export FINNHUB_API_KEY=your_api_key_here
```

### 3. Build and Run

```bash
cd fix-exchange-simulator
mvn clean package
mvn spring-boot:run
```

The exchange will:
- Start a FIX acceptor on port **9876**
- Start a REST API on port **8080**
- Initialize liquidity provider (quotes posted on first order per symbol)

### 4. Test with the Test Client

```bash
mvn exec:java -Dexec.mainClass="com.example.exchange.TestClient" -Dexec.classpathScope=test
```

## REST API Endpoints

### Order Book

```bash
# Get order book for a symbol
curl http://localhost:8080/api/exchange/orderbook/AAPL

# Response:
{
  "symbol": "AAPL",
  "bids": [
    {"price": 149.985, "quantity": 1000},
    {"price": 149.970, "quantity": 2000}
  ],
  "asks": [
    {"price": 150.015, "quantity": 1000},
    {"price": 150.030, "quantity": 2000}
  ],
  "spread": 0.03,
  "spreadBps": 2.0
}

# List all active symbols
curl http://localhost:8080/api/exchange/symbols
```

### Liquidity Provider

```bash
# Get liquidity provider status
curl http://localhost:8080/api/liquidity/status

# Pre-warm liquidity for a symbol (before trading)
curl -X POST http://localhost:8080/api/liquidity/setup/AAPL

# Check if symbol has liquidity
curl http://localhost:8080/api/liquidity/check/AAPL

# Get current price from Finnhub
curl http://localhost:8080/api/liquidity/price/AAPL

# Get all cached prices
curl http://localhost:8080/api/liquidity/prices

# Force refresh price
curl -X POST http://localhost:8080/api/liquidity/price/refresh/AAPL

# Clear price cache
curl -X POST http://localhost:8080/api/liquidity/cache/clear
```

### Health

```bash
# Health check
curl http://localhost:8080/api/health
```

## Configuration

### Application Properties

```yaml
# application.yml
server:
  port: 8080

fix:
  acceptor:
    port: 9876

liquidity-provider:
  enabled: true
  
  finnhub:
    api-key: ${FINNHUB_API_KEY:}
    cache-ttl-seconds: 30
  
  # Quote structure
  levels: 5                    # Price levels per side
  base-spread-bps: 10          # Spread at best price
  level-increment-bps: 5       # Additional spread per level
  base-quantity: 100           # Size at best bid/ask
  quantity-multiplier: 2       # Size multiplier per level
  
  # Fallback when Finnhub unavailable
  fallback-price: 100.00
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `FINNHUB_API_KEY` | Finnhub API key | (required) |
| `LIQUIDITY_ENABLED` | Enable liquidity provider | true |
| `LIQUIDITY_LEVELS` | Number of price levels | 5 |
| `LIQUIDITY_BASE_SPREAD_BPS` | Spread in basis points | 10 |
| `FIX_ACCEPTOR_PORT` | FIX protocol port | 9876 |

## Project Structure

```
fix-exchange-simulator/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/example/exchange/
│   │   │   ├── ExchangeSimulatorApplication.java
│   │   │   ├── config/
│   │   │   │   └── FixConfig.java
│   │   │   ├── controller/
│   │   │   │   ├── ExchangeController.java
│   │   │   │   └── LiquidityController.java
│   │   │   ├── engine/
│   │   │   │   ├── MatchingEngine.java
│   │   │   │   └── OrderBook.java
│   │   │   ├── fix/
│   │   │   │   └── ExchangeFixApplication.java
│   │   │   ├── liquidity/
│   │   │   │   ├── LiquidityProvider.java
│   │   │   │   ├── LiquidityProfile.java
│   │   │   │   └── FinnhubPriceService.java
│   │   │   └── model/
│   │   │       ├── Execution.java
│   │   │       ├── Order.java
│   │   │       └── OrderBook.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── quickfix-server.cfg
│   └── test/java/com/example/exchange/
│       └── TestClient.java
```

## Integration with FIX Client

The exchange works directly with the `fix-client` application:

```
┌─────────────────┐     ┌─────────────────┐     ┌──────────────────┐
│  Trading UI     ────▶   FIX Client      ────▶    Exchange       
│  (Dash:8050)    │REST │  (Spring:8081)  │ FIX │  (Spring:9876)   │
└─────────────────┘     └────────┬────────┘     └────────┬─────────┘
                                 │                       │
                                 │                       │
                                 ▼                       ▼
                        ┌─────────────────┐     ┌─────────────────┐
                        │     Redis       │     │ Matching Engine │
                        │   (Pub/Sub)     │     │ + Liquidity     │
                        └────────┬────────┘     └─────────────────┘
                                 │
                                 ▼
                        ┌─────────────────┐
                        │Portfolio Blotter│
                        │  (Dash:8060)    │
                        └─────────────────┘
```

Orders from the Trading UI:
1. Flow through fix-client
2. Arrive at the exchange via FIX
3. Match against market maker liquidity
4. Return fill execution reports
5. Appear in the Portfolio Blotter

## Disabling Liquidity Provider

For a "pure" exchange without automatic liquidity:

```yaml
liquidity-provider:
  enabled: false
```

Orders will rest in the book until matching counter-orders arrive.

## Troubleshooting

### "No liquidity for symbol"
- First order triggers liquidity setup (~100-200ms delay)
- Subsequent orders match instantly
- Pre-warm with: `curl -X POST http://localhost:8080/api/liquidity/setup/AAPL`

### "Finnhub API error"
- Verify API key is set correctly
- Check rate limits (60 calls/min on free tier)
- Fallback price will be used if Finnhub unavailable

### Orders Not Filling
- Check order book: `curl http://localhost:8080/api/exchange/orderbook/AAPL`
- Verify price crosses the spread
- Market orders always fill against available liquidity

### FIX Connection Issues
- Ensure no other process on port 9876
- Check SenderCompID/TargetCompID match client config
- View FIX logs in `target/log/fix/`

## Performance Notes

- **First Order**: ~100-200ms (Finnhub API call)
- **Subsequent Orders**: <1ms (in-memory matching)
- **Order Book**: ConcurrentSkipListMap for thread-safe sorted access
- **Matching**: O(log n) price level lookup + O(1) FIFO at each level