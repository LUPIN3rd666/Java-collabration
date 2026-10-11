import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Multi-Agent Continuous Auction Stock Trading Simulation System
 * Basic runnable version for Java 11+.
 *
 * Save as StockTradingSimulation.java, then run:
 *   javac StockTradingSimulation.java
 *   java StockTradingSimulation
 */
public class StockTradingSimulation {

    enum Side { BUY, SELL }

    static class Order {
        final long id;
        final String traderId;
        final String symbol;
        final Side side;
        final double price;
        int quantity;
        final long timestamp;

        Order(long id, String traderId, String symbol, Side side,
              double price, int quantity, long timestamp) {
            this.id = id;
            this.traderId = traderId;
            this.symbol = symbol;
            this.side = side;
            this.price = price;
            this.quantity = quantity;
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "#%d %s %s %d @ %.2f (remaining %d)",
                    id, traderId, side, quantity, price, quantity);
        }
    }

    static class Trade {
        final String symbol;
        final double price;
        final int quantity;
        final String buyer;
        final String seller;
        final LocalDateTime time;

        Trade(String symbol, double price, int quantity,
              String buyer, String seller) {
            this.symbol = symbol;
            this.price = price;
            this.quantity = quantity;
            this.buyer = buyer;
            this.seller = seller;
            this.time = LocalDateTime.now();
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s | %s | %d shares @ %.2f | buyer=%s seller=%s",
                    time.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    symbol, quantity, price, buyer, seller);
        }
    }

    static class Account {
        final String traderId;
        final double initialCash;
        double cash;
        final Map<String, Integer> holdings = new HashMap<>();

        Account(String traderId, double initialCash) {
            this.traderId = traderId;
            this.initialCash = initialCash;
            this.cash = initialCash;
        }

        int sharesOf(String symbol) {
            return holdings.getOrDefault(symbol, 0);
        }

        void addShares(String symbol, int quantity) {
            holdings.put(symbol, sharesOf(symbol) + quantity);
        }

        void removeShares(String symbol, int quantity) {
            int remaining = sharesOf(symbol) - quantity;
            if (remaining == 0) holdings.remove(symbol);
            else holdings.put(symbol, remaining);
        }

        double equity(Map<String, Double> prices) {
            double value = cash;
            for (Map.Entry<String, Integer> entry : holdings.entrySet()) {
                value += entry.getValue() * prices.getOrDefault(entry.getKey(), 0.0);
            }
            return value;
        }

        double returnRate(Map<String, Double> prices) {
            return (equity(prices) - initialCash) / initialCash * 100.0;
        }
    }

    static class OrderBook {
        // Highest bid first; earlier order first at the same price.
        final PriorityQueue<Order> bids = new PriorityQueue<>((a, b) -> {
            int priceCompare = Double.compare(b.price, a.price);
            return priceCompare != 0 ? priceCompare : Long.compare(a.timestamp, b.timestamp);
        });
        // Lowest ask first; earlier order first at the same price.
        final PriorityQueue<Order> asks = new PriorityQueue<>((a, b) -> {
            int priceCompare = Double.compare(a.price, b.price);
            return priceCompare != 0 ? priceCompare : Long.compare(a.timestamp, b.timestamp);
        });
    }

    static class MatchingEngine {
        final Map<String, OrderBook> books = new HashMap<>();
        final Map<String, Account> accounts = new LinkedHashMap<>();
        final Map<String, Double> lastPrices = new HashMap<>();
        final List<Trade> trades = new ArrayList<>();
        long nextOrderId = 1;
        long nextTimestamp = 1;

        MatchingEngine(Map<String, Double> initialPrices) {
            for (Map.Entry<String, Double> entry : initialPrices.entrySet()) {
                books.put(entry.getKey(), new OrderBook());
                lastPrices.put(entry.getKey(), entry.getValue());
            }
        }

        void addAccount(String traderId, double initialCash) {
            if (initialCash <= 0) throw new IllegalArgumentException("Initial cash must be positive.");
            if (accounts.containsKey(traderId)) throw new IllegalArgumentException("Trader already exists.");
            accounts.put(traderId, new Account(traderId, initialCash));
        }

        synchronized void submitLimitOrder(String traderId, String symbol, Side side,
                                           double price, int quantity) {
            Account account = accounts.get(traderId);
            if (account == null) {
                System.out.println("Error: unknown trader.");
                return;
            }
            if (!books.containsKey(symbol)) {
                System.out.println("Error: unknown stock symbol.");
                return;
            }
            if (price <= 0 || quantity <= 0 || !Double.isFinite(price)) {
                System.out.println("Error: price and quantity must be positive.");
                return;
            }

            // Reserve checks are performed before accepting the order.
            // This starter version does not reserve funds/shares for resting orders,
            // so checks are repeated at execution time as well.
            if (side == Side.BUY && account.cash + 1e-9 < price * quantity) {
                System.out.println("Error: insufficient cash for this buy order.");
                return;
            }
            if (side == Side.SELL && account.sharesOf(symbol) < quantity) {
                System.out.println("Error: insufficient shares for this sell order.");
                return;
            }

            Order order = new Order(nextOrderId++, traderId, symbol, side,
                    price, quantity, nextTimestamp++);
            OrderBook book = books.get(symbol);
            System.out.println("Accepted: " + order);
            matchOrder(order, book);
        }

        private void matchOrder(Order incoming, OrderBook book) {
            PriorityQueue<Order> opposite = incoming.side == Side.BUY ? book.asks : book.bids;

            while (incoming.quantity > 0 && !opposite.isEmpty()) {
                Order resting = opposite.peek();
                boolean canMatch = incoming.side == Side.BUY
                        ? incoming.price >= resting.price
                        : incoming.price <= resting.price;
                if (!canMatch) break;

                // Prevent a trader from trading with their own resting order.
                if (incoming.traderId.equals(resting.traderId)) {
                    // Avoid an infinite loop: keep the incoming order resting instead.
                    break;
                }

                Account buyer = accounts.get(incoming.side == Side.BUY
                        ? incoming.traderId : resting.traderId);
                Account seller = accounts.get(incoming.side == Side.SELL
                        ? incoming.traderId : resting.traderId);

                int quantity = Math.min(incoming.quantity, resting.quantity);
                double tradePrice = resting.price;

                // Re-check execution-time balances/holdings.
                if (buyer.cash + 1e-9 < tradePrice * quantity) {
                    System.out.println("Trade skipped: buyer has insufficient cash.");
                    break;
                }
                if (seller.sharesOf(incoming.symbol) < quantity) {
                    System.out.println("Trade skipped: seller has insufficient shares.");
                    break;
                }

                buyer.cash -= tradePrice * quantity;
                buyer.addShares(incoming.symbol, quantity);
                seller.cash += tradePrice * quantity;
                seller.removeShares(incoming.symbol, quantity);

                incoming.quantity -= quantity;
                resting.quantity -= quantity;
                if (resting.quantity == 0) opposite.poll();

                Trade trade = new Trade(incoming.symbol, tradePrice, quantity,
                        buyer.traderId, seller.traderId);
                trades.add(trade);
                lastPrices.put(incoming.symbol, tradePrice);
                System.out.println("TRADE: " + trade);
            }

            if (incoming.quantity > 0) {
                PriorityQueue<Order> own = incoming.side == Side.BUY ? book.bids : book.asks;
                own.offer(incoming);
                System.out.println("Resting order added to order book.");
            }
        }

        synchronized void showOrderBook(String symbol) {
            OrderBook book = books.get(symbol);
            if (book == null) {
                System.out.println("Unknown symbol.");
                return;
            }
            System.out.println("\nOrder book for " + symbol + " (last price: "
                    + String.format(Locale.US, "%.2f", lastPrices.get(symbol)) + ")");
            System.out.println("BIDS (buy):");
            printTop(book.bids);
            System.out.println("ASKS (sell):");
            printTop(book.asks);
        }

        private void printTop(PriorityQueue<Order> queue) {
            List<Order> orders = new ArrayList<>(queue);
            orders.sort(queue.comparator());
            if (orders.isEmpty()) {
                System.out.println("  (empty)");
                return;
            }
            for (int i = 0; i < Math.min(5, orders.size()); i++) {
                Order o = orders.get(i);
                System.out.printf(Locale.US, "  %s | %s | %d shares @ %.2f%n",
                        o.traderId, o.side, o.quantity, o.price);
            }
        }

        synchronized void showAccounts() {
            System.out.println("\n=== Account Summary ===");
            for (Account account : accounts.values()) {
                System.out.printf(Locale.US,
                        "%s | Cash: $%.2f | Equity: $%.2f | Return: %.2f%%%n",
                        account.traderId, account.cash,
                        account.equity(lastPrices), account.returnRate(lastPrices));
                if (account.holdings.isEmpty()) {
                    System.out.println("  Holdings: none");
                } else {
                    System.out.println("  Holdings: " + account.holdings);
                }
            }
        }

        synchronized void showTrades() {
            System.out.println("\n=== Trade History ===");
            if (trades.isEmpty()) {
                System.out.println("No trades yet.");
                return;
            }
            for (Trade trade : trades) System.out.println(trade);
        }

        synchronized void showStocks() {
            System.out.println("\n=== Stocks ===");
            for (String symbol : lastPrices.keySet()) {
                System.out.printf(Locale.US, "%s: $%.2f%n", symbol, lastPrices.get(symbol));
            }
        }
    }

    private static void printMenu() {
        System.out.println("\n========== Stock Trading Simulation ==========");
        System.out.println("1. Submit limit order");
        System.out.println("2. Show order book");
        System.out.println("3. Show accounts and performance");
        System.out.println("4. Show trade history");
        System.out.println("5. Show stock prices");
        System.out.println("6. Run a small demo");
        System.out.println("0. Exit");
        System.out.print("Choose: ");
    }

    public static void main(String[] args) {
        Map<String, Double> prices = new LinkedHashMap<>();
        prices.put("AAPL", 175.50);
        prices.put("GOOGL", 141.80);
        prices.put("TSLA", 245.30);

        MatchingEngine engine = new MatchingEngine(prices);
        engine.addAccount("Human_A", 100000.00);
        engine.addAccount("Human_B", 100000.00);
        engine.addAccount("AI_A", 50000.00);
        engine.addAccount("AI_B", 50000.00);

        Scanner scanner = new Scanner(System.in);
        System.out.println("Welcome! Available traders: Human_A, Human_B, AI_A, AI_B");
        engine.showStocks();

        while (true) {
            printMenu();
            String choice = scanner.nextLine().trim();
            try {
                switch (choice) {
                    case "1":
                        System.out.print("Trader ID: ");
                        String trader = scanner.nextLine().trim();
                        System.out.print("Stock symbol (AAPL/GOOGL/TSLA): ");
                        String symbol = scanner.nextLine().trim().toUpperCase(Locale.ROOT);
                        System.out.print("Side (BUY/SELL): ");
                        Side side = Side.valueOf(scanner.nextLine().trim().toUpperCase(Locale.ROOT));
                        System.out.print("Limit price: ");
                        double price = Double.parseDouble(scanner.nextLine().trim());
                        System.out.print("Quantity (shares): ");
                        int quantity = Integer.parseInt(scanner.nextLine().trim());
                        engine.submitLimitOrder(trader, symbol, side, price, quantity);
                        break;
                    case "2":
                        System.out.print("Stock symbol: ");
                        engine.showOrderBook(scanner.nextLine().trim().toUpperCase(Locale.ROOT));
                        break;
                    case "3":
                        engine.showAccounts();
                        break;
                    case "4":
                        engine.showTrades();
                        break;
                    case "5":
                        engine.showStocks();
                        break;
                    case "6":
                        runDemo(engine);
                        break;
                    case "0":
                        System.out.println("Simulation ended.");
                        engine.showAccounts();
                        return;
                    default:
                        System.out.println("Invalid choice. Please enter 0-6.");
                }
            } catch (IllegalArgumentException ex) {
                System.out.println("Invalid input: " + ex.getMessage());
            } catch (Exception ex) {
                System.out.println("Something went wrong: " + ex.getMessage());
            }
        }
    }

    private static void runDemo(MatchingEngine engine) {
        System.out.println("\n--- Demo: a sell order, then a matching buy order ---");
        // Give Human_A 10 AAPL shares for this demonstration and account for
        // their value by reducing cash, so total equity stays unchanged.
        Account demoSeller = engine.accounts.get("Human_A");
        if (demoSeller.sharesOf("AAPL") < 10) {
            int needed = 10 - demoSeller.sharesOf("AAPL");
            double cost = needed * engine.lastPrices.get("AAPL");
            if (demoSeller.cash >= cost) {
                demoSeller.cash -= cost;
                demoSeller.addShares("AAPL", needed);
            }
        }
        engine.submitLimitOrder("Human_A", "AAPL", Side.SELL, 174.00, 10);
        engine.submitLimitOrder("AI_A", "AAPL", Side.BUY, 175.00, 5);
        engine.submitLimitOrder("Human_B", "AAPL", Side.BUY, 174.00, 5);
        engine.showOrderBook("AAPL");
        engine.showAccounts();
    }
}
