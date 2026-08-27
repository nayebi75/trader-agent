package org.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.agent.service.dto.CandleDTO;
import org.agent.service.dto.CryptoCurrencyDTO;
import org.agent.utils.TradeUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

@Slf4j
public class ExchangeClient {

    private static final String BASE_URL = "https://api.lbank.info";
    private static final String TICKER_24H_PATH = "/v2/ticker/24hr.do";
    private static final String KLINE_PATH = "/v2/kline.do";

    private static final int MAX_KLINE_SIZE = 2000;
    private static final int MAX_HTTP_ATTEMPTS = 3;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final Set<Integer> SUPPORTED_MINUTE_INTERVALS = Set.of(1, 5, 15, 30);
    private static final Set<Integer> SUPPORTED_HOUR_INTERVALS = Set.of(1, 4, 8, 12);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ExchangeClient() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<CryptoCurrencyDTO> getAvailableCryptoCurrencies() {

        URI uri = buildUri(TICKER_24H_PATH, Map.of("symbol", "all"));
        JsonNode root = executeGet(uri);
        JsonNode data = extractDataArray(root, "24h ticker");

        List<CryptoCurrencyDTO> result = new ArrayList<>();

        for (JsonNode node : data) {
            try {
                CryptoCurrencyDTO cryptoCurrency = parseTicker(node);

                if (cryptoCurrency != null) {
                    result.add(cryptoCurrency);
                }
            } catch (Exception e) {
                log.warn("Skipping malformed LBank ticker entry: {}", e.getMessage());
            }
        }

        log.info("Fetched {} cryptocurrencies from LBank", result.size());

        return result;
    }

    public List<CandleDTO> fetchMinutelyClosingPrices(String symbol, int minutesInterval, int size) {
        validateMinuteInterval(minutesInterval);

        long timestamp = TradeUtils.getMinuteTimestamp(minutesInterval, size);
        return fetchKlines(symbol, "minute" + minutesInterval, size, timestamp);
    }

    public List<CandleDTO> fetchMinutelyClosingPricesWithTimestamp(String symbol, int minutesInterval, int size,
                                                                   long timestamp) {
        validateMinuteInterval(minutesInterval);
        return fetchKlines(symbol, "minute" + minutesInterval, size, timestamp);
    }

    public List<CandleDTO> fetchHourlyClosingPrices(String symbol, int hoursInterval, int size) {
        validateHourInterval(hoursInterval);

        long timestamp = TradeUtils.getHourTimestamp(hoursInterval, size);
        return fetchKlines(symbol, "hour" + hoursInterval, size, timestamp);
    }

    public List<CandleDTO> fetchHourlyClosingPricesWithTimestamp(String symbol, int hoursInterval, int size,
                                                                 long timestamp) {
        validateHourInterval(hoursInterval);
        return fetchKlines(symbol, "hour" + hoursInterval, size, timestamp);
    }

    public List<CandleDTO> fetchDailyClosingPrices(String symbol, int daysInterval, int size) {

        if (daysInterval != 1) {
            throw new IllegalArgumentException("LBank supports day1, but requested day" + daysInterval);
        }

        long timestamp = TradeUtils.getDayTimestamp(daysInterval, size);
        return fetchKlines(symbol, "day1", size, timestamp);
    }

    private List<CandleDTO> fetchKlines(String symbol, String type, int size, long timestamp) {

        validateSymbol(symbol);
        validateKlineSize(size);

        if (timestamp <= 0) {
            throw new IllegalArgumentException("K-line timestamp must be positive");
        }

        URI uri = buildUri(KLINE_PATH, Map.of(
                "symbol", symbol,
                "size", String.valueOf(size),
                "type", type,
                "time", String.valueOf(timestamp)
        ));

        JsonNode root = executeGet(uri);
        JsonNode data = extractDataArray(root, "K-line");

        /*
         * TreeMap gives us:
         *
         * 1. chronological order
         * 2. duplicate timestamp removal
         *
         * We still sort again in StrategyService defensively, but ExchangeClient itself should return clean data.
         */
        Map<Long, CandleDTO> candlesByTimestamp = new TreeMap<>();

        for (JsonNode row : data) {
            try {
                CandleDTO candle = parseCandle(row);

                if (candle != null) {
                    candlesByTimestamp.put(candle.getTimestamp(), candle);
                }
            } catch (Exception e) {
                log.warn("Skipping malformed K-line row for symbol={}: {}", symbol, e.getMessage());
            }
        }

        List<CandleDTO> candles = new ArrayList<>(candlesByTimestamp.values());

        log.trace(
                "Fetched {} {} candles for symbol={}, requestedSize={}, fromTimestamp={}",
                candles.size(),
                type,
                symbol,
                size,
                timestamp
        );

        return candles;
    }

    private CryptoCurrencyDTO parseTicker(JsonNode node) {

        if (node == null || !node.isObject()) {
            return null;
        }

        JsonNode ticker = node.get("ticker");

        if (ticker == null || !ticker.isObject()) {
            return null;
        }

        String symbol = getRequiredText(node, "symbol");

        CryptoCurrencyDTO cryptoCurrency = new CryptoCurrencyDTO();
        cryptoCurrency.setSymbol(symbol);
        cryptoCurrency.setTimestamp(node.path("timestamp").asLong());
        cryptoCurrency.setChange(getRequiredText(ticker, "change"));
        cryptoCurrency.setHigh(getRequiredText(ticker, "high"));
        cryptoCurrency.setLow(getRequiredText(ticker, "low"));
        cryptoCurrency.setLatest(getRequiredText(ticker, "latest"));
        cryptoCurrency.setVol(getRequiredText(ticker, "vol"));
        cryptoCurrency.setTurnover(getRequiredText(ticker, "turnover"));

        return cryptoCurrency;
    }

    private CandleDTO parseCandle(JsonNode row) {

        if (row == null || !row.isArray() || row.size() < 6) {
            return null;
        }

        CandleDTO candle = CandleDTO.builder()
                .timestamp(row.get(0).asLong())
                .open(row.get(1).asDouble())
                .high(row.get(2).asDouble())
                .low(row.get(3).asDouble())
                .close(row.get(4).asDouble())
                .volume(row.get(5).asDouble())
                .build();

        return isValidCandle(candle) ? candle : null;
    }

    private JsonNode executeGet(URI uri) {

        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", "application/json")
                        .header("User-Agent", "trader-agent/1.0")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    JsonNode root = objectMapper.readTree(response.body());
                    validateLBankResponse(root);
                    return root;
                }

                if (!isRetryableStatus(response.statusCode()) || attempt == MAX_HTTP_ATTEMPTS) {
                    throw new IllegalStateException(
                            "LBank HTTP request failed. status=" + response.statusCode() + ", uri=" + uri);
                }

                log.warn(
                        "LBank request failed with retryable status={}, attempt={}/{}, uri={}",
                        response.statusCode(),
                        attempt,
                        MAX_HTTP_ATTEMPTS,
                        uri
                );

                sleepBeforeRetry(attempt);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("LBank HTTP request interrupted: " + uri, e);

            } catch (IOException | RuntimeException e) {
                lastException = e;

                if (attempt == MAX_HTTP_ATTEMPTS) {
                    break;
                }

                log.warn(
                        "LBank request failed, attempt={}/{}, uri={}, reason={}",
                        attempt,
                        MAX_HTTP_ATTEMPTS,
                        uri,
                        e.getMessage()
                );

                sleepBeforeRetry(attempt);
            }
        }

        throw new IllegalStateException("Failed to call LBank after " + MAX_HTTP_ATTEMPTS + " attempts: " + uri,
                lastException);
    }

    private void validateLBankResponse(JsonNode root) {

        if (root == null || root.isNull()) {
            throw new IllegalStateException("LBank returned an empty JSON response");
        }

        /*
         * Current V2 response:
         *
         * {
         *   "result": "true",
         *   "error_code": 0,
         *   "data": [...]
         * }
         *
         * We also tolerate a raw array for compatibility with historical LBank response formats.
         */
        if (root.isArray()) {
            return;
        }

        if (!root.isObject()) {
            throw new IllegalStateException("Unexpected LBank response type");
        }

        int errorCode = root.path("error_code").asInt(0);
        String result = root.path("result").asText("true");

        if (errorCode != 0 || !"true".equalsIgnoreCase(result)) {
            String message = root.path("msg").asText("Unknown LBank error");

            throw new IllegalStateException(
                    "LBank API error. errorCode=" + errorCode + ", result=" + result + ", message=" + message);
        }
    }

    private JsonNode extractDataArray(JsonNode root, String operation) {

        /*
         * Backward compatibility with the historical endpoint format,
         * where the K-line response itself was directly an array.
         */
        if (root.isArray()) {
            return root;
        }

        JsonNode data = root.get("data");

        if (data == null || !data.isArray()) {
            throw new IllegalStateException("LBank " + operation + " response does not contain a data array");
        }

        return data;
    }

    private URI buildUri(String path, Map<String, String> parameters) {

        StringJoiner query = new StringJoiner("&");

        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            query.add(encode(entry.getKey()) + "=" + encode(entry.getValue()));
        }

        return URI.create(BASE_URL + path + "?" + query);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String getRequiredText(JsonNode node, String fieldName) {

        JsonNode value = node.get(fieldName);

        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }

        String text = value.asText();

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Empty required field: " + fieldName);
        }

        return text;
    }

    private boolean isValidCandle(CandleDTO candle) {

        if (candle == null || candle.getTimestamp() <= 0) {
            return false;
        }

        double open = candle.getOpen();
        double high = candle.getHigh();
        double low = candle.getLow();
        double close = candle.getClose();
        double volume = candle.getVolume();

        if (!Double.isFinite(open) || !Double.isFinite(high) || !Double.isFinite(low)
                || !Double.isFinite(close) || !Double.isFinite(volume)) {
            return false;
        }

        if (open <= 0 || high <= 0 || low <= 0 || close <= 0 || volume < 0) {
            return false;
        }

        return high >= Math.max(open, close) && low <= Math.min(open, close) && high >= low;
    }

    private void validateSymbol(String symbol) {

        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Cryptocurrency symbol cannot be null or blank");
        }

        if (!symbol.matches("[a-zA-Z0-9_\\-]+")) {
            throw new IllegalArgumentException("Invalid cryptocurrency symbol: " + symbol);
        }
    }

    private void validateKlineSize(int size) {

        if (size < 1 || size > MAX_KLINE_SIZE) {
            throw new IllegalArgumentException(
                    "LBank K-line size must be between 1 and " + MAX_KLINE_SIZE + ", requested=" + size);
        }
    }

    private void validateMinuteInterval(int interval) {

        if (!SUPPORTED_MINUTE_INTERVALS.contains(interval)) {
            throw new IllegalArgumentException(
                    "Unsupported minute interval: " + interval + ". Supported: " + SUPPORTED_MINUTE_INTERVALS);
        }
    }

    private void validateHourInterval(int interval) {

        if (!SUPPORTED_HOUR_INTERVALS.contains(interval)) {
            throw new IllegalArgumentException(
                    "Unsupported hour interval: " + interval + ". Supported: " + SUPPORTED_HOUR_INTERVALS);
        }
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private void sleepBeforeRetry(int attempt) {

        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry LBank request", e);
        }
    }
}