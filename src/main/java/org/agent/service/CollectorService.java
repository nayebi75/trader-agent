package org.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.agent.client.ExchangeClient;
import org.agent.service.dto.CryptoCurrencyDTO;
import org.agent.service.dto.TradeSignalDTO;
import org.agent.utils.DataUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public class CollectorService {

    private static final String QUOTE_CURRENCY_SUFFIX = "_usdt";

    /*
     * Initial market-universe parameters.
     *
     * These are screening parameters, not strategy parameters.
     * They should eventually be validated against historical results.
     */
    private static final BigDecimal MIN_24H_TURNOVER = new BigDecimal("1000000");
    private static final double MIN_24H_RANGE_PERCENT = 1.5;
    private static final double MAX_24H_ABS_CHANGE_PERCENT = 20.0;

    private static final int MAX_MARKETS_TO_ANALYZE = 300;
    private static final long SIGNAL_COOLDOWN_HOURS = 12;

    private final ExchangeClient exchangeClient = new ExchangeClient();

    public List<CryptoCurrencyDTO> collectSignals() {

        List<CryptoCurrencyDTO> allMarkets = exchangeClient.getAvailableCryptoCurrencies();

        if (allMarkets == null || allMarkets.isEmpty()) {
            log.warn("No cryptocurrencies returned by LBank");
            return Collections.emptyList();
        }

        log.info("Market universe -> fetched={}", allMarkets.size());

        /*
         * Stage 1:
         *
         * Only valid USDT markets with valid ticker values.
         */
        List<CryptoCurrencyDTO> validUsdtMarkets = allMarkets.stream()
                .filter(this::isValidUsdtMarket)
                .toList();

        /*
         * Stage 2:
         *
         * Remove illiquid markets.
         */
        List<CryptoCurrencyDTO> liquidMarkets = validUsdtMarkets.stream()
                .filter(this::hasEnoughLiquidity)
                .toList();

        /*
         * Stage 3:
         *
         * Remove markets with almost no daily price movement.
         *
         * There is little value in running a momentum strategy on a market
         * whose 24-hour range is extremely small.
         */
        List<CryptoCurrencyDTO> volatileMarkets = liquidMarkets.stream()
                .filter(this::hasEnoughVolatility)
                .toList();

        /*
         * Stage 4:
         *
         * Reject coins that have already experienced an extreme 24-hour move.
         *
         * We don't want to start expensive indicator analysis on obvious
         * pump/dump candidates.
         */
        List<CryptoCurrencyDTO> nonExtremeMarkets = volatileMarkets.stream()
                .filter(this::isNotExtremeMove)
                .toList();

        /*
         * Load previous signals once.
         *
         * Do not read DataUtils separately for every cryptocurrency.
         */
        Set<String> recentlySignaledSymbols = loadRecentlySignaledSymbols();

        /*
         * Stage 5:
         *
         * Avoid repeatedly creating signals for the same symbol during
         * a short period.
         */
        List<CryptoCurrencyDTO> availableMarkets = nonExtremeMarkets.stream()
                .filter(crypto -> !recentlySignaledSymbols.contains(normalizeSymbol(crypto.getSymbol())))
                .toList();

        /*
         * Stage 6:
         *
         * Analyze the most liquid markets first and limit the number of
         * expensive K-line / TA4J analyses.
         */
        List<CryptoCurrencyDTO> result = availableMarkets.stream()
                .sorted((first, second) -> getTurnover(second).compareTo(getTurnover(first)))
                .limit(MAX_MARKETS_TO_ANALYZE)
                .toList();

        logMarketUniverseStatistics(
                allMarkets.size(),
                validUsdtMarkets.size(),
                liquidMarkets.size(),
                volatileMarkets.size(),
                nonExtremeMarkets.size(),
                availableMarkets.size(),
                result.size(),
                recentlySignaledSymbols.size()
        );

        return result;
    }

    private boolean isValidUsdtMarket(CryptoCurrencyDTO crypto) {

        if (crypto == null) {
            return false;
        }

        String symbol = crypto.getSymbol();

        if (symbol == null || symbol.isBlank()) {
            return false;
        }

        if (!normalizeSymbol(symbol).endsWith(QUOTE_CURRENCY_SUFFIX)) {
            return false;
        }

        BigDecimal latest = parseDecimal(crypto.getLatest());
        BigDecimal high = parseDecimal(crypto.getHigh());
        BigDecimal low = parseDecimal(crypto.getLow());
        BigDecimal volume = parseDecimal(crypto.getVol());
        BigDecimal turnover = parseDecimal(crypto.getTurnover());
        BigDecimal change = parseDecimal(crypto.getChange());

        if (latest == null || high == null || low == null || volume == null || turnover == null || change == null) {
            return false;
        }

        if (latest.compareTo(BigDecimal.ZERO) <= 0 || high.compareTo(BigDecimal.ZERO) <= 0
                || low.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        if (volume.compareTo(BigDecimal.ZERO) <= 0 || turnover.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        if (high.compareTo(low) < 0) {
            return false;
        }

        /*
         * Latest price should normally be inside the reported daily range.
         *
         * Allow equality with high/low.
         */
        return latest.compareTo(low) >= 0 && latest.compareTo(high) <= 0;
    }

    private boolean hasEnoughLiquidity(CryptoCurrencyDTO crypto) {
        return getTurnover(crypto).compareTo(MIN_24H_TURNOVER) >= 0;
    }

    private boolean hasEnoughVolatility(CryptoCurrencyDTO crypto) {

        BigDecimal high = parseDecimal(crypto.getHigh());
        BigDecimal low = parseDecimal(crypto.getLow());

        if (high == null || low == null || low.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        BigDecimal range = high.subtract(low);

        double rangePercent = range
                .divide(low, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();

        return rangePercent >= MIN_24H_RANGE_PERCENT;
    }

    private boolean isNotExtremeMove(CryptoCurrencyDTO crypto) {

        BigDecimal change = parseDecimal(crypto.getChange());

        if (change == null) {
            return false;
        }

        /*
         * LBank's "change" field is already expressed as percentage.
         *
         * Example:
         * change = 1.13 means +1.13%, not +113%.
         */
        return change.abs().compareTo(BigDecimal.valueOf(MAX_24H_ABS_CHANGE_PERCENT)) <= 0;
    }

    private Set<String> loadRecentlySignaledSymbols() {

        try {
            List<TradeSignalDTO> previousSignals = DataUtils.loadTradeSignals();

            if (previousSignals == null || previousSignals.isEmpty()) {
                return Collections.emptySet();
            }

            long cutoffTimestamp = Instant.now()
                    .minusSeconds(SIGNAL_COOLDOWN_HOURS * 60L * 60L)
                    .getEpochSecond();

            Set<String> recentlySignaledSymbols = new HashSet<>();

            for (TradeSignalDTO signal : previousSignals) {

                if (signal == null || signal.getSymbol() == null || signal.getSymbol().isBlank()) {
                    continue;
                }

                if (signal.getTimestamp() >= cutoffTimestamp) {
                    recentlySignaledSymbols.add(normalizeSymbol(signal.getSymbol()));
                }
            }

            return recentlySignaledSymbols;

        } catch (Exception e) {
            log.error("Failed to load previous signals. Recent-signal filtering will be skipped", e);
            return Collections.emptySet();
        }
    }

    private BigDecimal getTurnover(CryptoCurrencyDTO crypto) {

        if (crypto == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal turnover = parseDecimal(crypto.getTurnover());
        return turnover != null ? turnover : BigDecimal.ZERO;
    }

    private BigDecimal parseDecimal(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toLowerCase();
    }

    private void logMarketUniverseStatistics(int fetched, int validUsdt, int liquid, int volatileMarkets,
                                             int nonExtreme, int withoutRecentSignals, int selected,
                                             int recentlySignaled) {

        log.info(
                "Market universe -> fetched={}, valid USDT={}, liquid={}, volatile={}, nonExtreme={}, "
                        + "withoutRecentSignals={}, selected={}, recentlySignaled={}",
                fetched,
                validUsdt,
                liquid,
                volatileMarkets,
                nonExtreme,
                withoutRecentSignals,
                selected,
                recentlySignaled
        );
    }
}