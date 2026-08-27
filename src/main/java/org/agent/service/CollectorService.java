package org.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.agent.client.ExchangeClient;
import org.agent.service.dto.CryptoCurrencyDTO;
import org.agent.service.dto.TradeSignalDTO;
import org.agent.utils.DataUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public class CollectorService {

    private static final String QUOTE_CURRENCY_SUFFIX = "_usdt";
    private static final long SIGNAL_COOLDOWN_HOURS = 12;

    private final ExchangeClient exchangeClient = new ExchangeClient();

    public List<CryptoCurrencyDTO> collectSignals() {

        List<CryptoCurrencyDTO> availableCryptoCurrencies = exchangeClient.getAvailableCryptoCurrencies();

        if (availableCryptoCurrencies == null || availableCryptoCurrencies.isEmpty()) {
            log.warn("No cryptocurrencies returned by exchange");
            return Collections.emptyList();
        }

        log.info("Fetched {} cryptocurrencies from exchange", availableCryptoCurrencies.size());

        Set<String> recentlySignaledSymbols = loadRecentlySignaledSymbols();

        List<CryptoCurrencyDTO> result = availableCryptoCurrencies.stream()
                .filter(this::isValidCryptoCurrency)
                .filter(crypto -> !recentlySignaledSymbols.contains(crypto.getSymbol()))
                .sorted(Comparator.comparing(this::getTurnover).reversed())
                .toList();

        log.info("Collected {} eligible USDT cryptocurrencies, excluded {} recently signaled symbols",
                result.size(), recentlySignaledSymbols.size());

        return result;
    }

    private Set<String> loadRecentlySignaledSymbols() {

        try {
            List<TradeSignalDTO> previousSignals = DataUtils.loadTradeSignals();

            if (previousSignals == null || previousSignals.isEmpty()) {
                return Collections.emptySet();
            }

            long cutoffTimestamp = Instant.now().minusSeconds(SIGNAL_COOLDOWN_HOURS * 60 * 60).getEpochSecond();

            Set<String> recentlySignaledSymbols = new HashSet<>();

            for (TradeSignalDTO signal : previousSignals) {

                if (signal == null || signal.getSymbol() == null || signal.getSymbol().isBlank()) {
                    continue;
                }

                if (signal.getTimestamp() >= cutoffTimestamp) {
                    recentlySignaledSymbols.add(signal.getSymbol());
                }
            }

            return recentlySignaledSymbols;

        } catch (Exception e) {
            log.error("Failed to load previous trade signals. Duplicate-signal filtering will be skipped", e);
            return Collections.emptySet();
        }
    }

    private boolean isValidCryptoCurrency(CryptoCurrencyDTO cryptoCurrency) {

        if (cryptoCurrency == null) {
            return false;
        }

        String symbol = cryptoCurrency.getSymbol();

        if (symbol == null || symbol.isBlank() || !symbol.endsWith(QUOTE_CURRENCY_SUFFIX)) {
            return false;
        }

        if (!isPositiveNumber(cryptoCurrency.getLatest())) {
            log.debug("Skipping symbol {} because latest price is invalid: {}", symbol, cryptoCurrency.getLatest());
            return false;
        }

        if (!isNonNegativeNumber(cryptoCurrency.getTurnover())) {
            log.debug("Skipping symbol {} because turnover is invalid: {}", symbol, cryptoCurrency.getTurnover());
            return false;
        }

        return true;
    }

    private BigDecimal getTurnover(CryptoCurrencyDTO cryptoCurrency) {

        if (cryptoCurrency == null || cryptoCurrency.getTurnover() == null) {
            return BigDecimal.ZERO;
        }

        try {
            return new BigDecimal(cryptoCurrency.getTurnover());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private boolean isPositiveNumber(String value) {

        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            return new BigDecimal(value).compareTo(BigDecimal.ZERO) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isNonNegativeNumber(String value) {

        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            return new BigDecimal(value).compareTo(BigDecimal.ZERO) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}