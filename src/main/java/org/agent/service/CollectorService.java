package org.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.agent.client.ExchangeClient;
import org.agent.service.dto.CryptoCurrencyDTO;
import org.agent.service.dto.TradeSignalDTO;
import org.agent.utils.DataUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class CollectorService {

    private final ExchangeClient exchangeClient = new ExchangeClient();

    public List<CryptoCurrencyDTO> collectSignals() {
        List<CryptoCurrencyDTO> result = new ArrayList<>();
        List<CryptoCurrencyDTO> all = exchangeClient.getAvailableCryptoCurrencies();
        log.info("fetched {} cryptocurrencies", all.size());

        Map<String, CryptoCurrencyDTO> symbolMap = all.stream()
                .filter(cryptoCurrencyDTO -> cryptoCurrencyDTO.getSymbol().endsWith("_usdt"))
//                .filter(cryptoCurrencyDTO -> Double.parseDouble(cryptoCurrencyDTO.getLatest()) > 1.0)
                .filter(cryptoCurrencyDTO -> isNotPresentInSignals(cryptoCurrencyDTO.getSymbol()))
                .collect(Collectors.toMap(CryptoCurrencyDTO::getSymbol,
                        cryptoCurrencyDTO -> cryptoCurrencyDTO));

        List<String> hitTpSignalsByFrequencyOrder = loadHitTpTradeSignalsByOrder();
        for (String symbol : hitTpSignalsByFrequencyOrder) {
            if (symbolMap.containsKey(symbol)) {
                result.add(symbolMap.get(symbol));
                symbolMap.remove(symbol);
            }
        }

        result.addAll(symbolMap.values().stream().sorted(Comparator.reverseOrder()).toList());
        return result;
    }

    private List<String> loadHitTpTradeSignalsByOrder() {
        List<TradeSignalDTO> hitTpSignals = DataUtils.loadHitTpTradeSignals();
        return hitTpSignals.stream().map(TradeSignalDTO::getSymbol)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    private static boolean isNotPresentInSignals(String cryptoCurrency) {
        try {
            List<TradeSignalDTO> previousSignals = DataUtils.loadTradeSignals();
            return previousSignals.stream()
                    .filter(signal -> signal.getSymbol().equals(cryptoCurrency))
                    .filter(signal -> {
                        long timestamp = signal.getTimestamp();
                        ZonedDateTime signalZonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);
                        return !signalZonedDateTime.isBefore(ZonedDateTime.now(ZoneOffset.UTC).minusHours(12));
                    }).toList().isEmpty();
        } catch (Exception e) {
            return true;
        }
    }
}
