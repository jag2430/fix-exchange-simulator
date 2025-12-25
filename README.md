# FIX Exchange Simulator

A Spring Boot application that simulates a financial exchange using QuickFIX/J for FIX protocol communication.

## Features

- FIX 4.4 protocol support
- Order matching engine with price-time priority
- Limit and market order support
- Order cancellation
- REST API for monitoring order books
- Multiple client session support

## Prerequisites

- Java 17+
- Maven 3.6+

## Building

```bash
cd fix-exchange-simulator
mvn clean package
```

## Running the Exchange

```bash
mvn spring-boot:run
```

The exchange will:
- Start a FIX acceptor on port 9876
- Start a REST API on port 8080

## Testing with the Test Client

In a separate terminal, after starting the exchange:

```bash
mvn exec:java -Dexec.mainClass="com.example.exchange.TestClient" -Dexec.classpathScope=test
```

## REST API Endpoints

### Get Order Book
```bash
curl http://localhost:8080/api/exchange/orderbook/AAPL
```

### List Active Symbols
```bash
curl http://localhost:8080/api/exchange/symbols
```

## FIX Session Configuration

Clients should connect with:
- Host: localhost
- Port: 9876
- BeginString: FIX.4.4
- SenderCompID: CLIENT1 (or CLIENT2)
- TargetCompID: EXCHANGE

## Supported FIX Messages

### Incoming
- NewOrderSingle (D)
- OrderCancelRequest (F)

### Outgoing
- ExecutionReport (8)

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
│   │   │   │   └── ExchangeController.java
│   │   │   ├── engine/
│   │   │   │   ├── MatchingEngine.java
│   │   │   │   └── OrderBook.java
│   │   │   ├── fix/
│   │   │   │   └── ExchangeFixApplication.java
│   │   │   └── model/
│   │   │       ├── Execution.java
│   │   │       └── Order.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── quickfix-server.cfg
│   └── test/java/com/example/exchange/
│       └── TestClient.java
```

## License

MIT
