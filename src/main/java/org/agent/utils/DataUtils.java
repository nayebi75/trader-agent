package org.agent.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.agent.constants.SignalStatus;
import org.agent.service.dto.TradeSignalDTO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
public class DataUtils {

    private static final String SIGNAL_FILE_NAME = "signals.json";
    private static final String HIT_TP_SIGNAL_FILE_NAME = "hitTpSignals.json";
    private static final String HIT_SL_SIGNAL_FILE_NAME = "hitSlSignals.json";
    private static final String ANALYZED_CANDLES_FILE_NAME = "analyzedCandles.json";

    private static final TypeReference<List<TradeSignalDTO>> SIGNAL_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Long>> ANALYZED_CANDLES_TYPE = new TypeReference<>() {
    };

    static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module());

    private static final Path DATA_DIRECTORY = Path.of("data");

    public static synchronized List<TradeSignalDTO> loadTradeSignals() {
        return retrieveTradeSignalsFromFile(signalFile());
    }

    public static synchronized List<TradeSignalDTO> loadHitTpTradeSignals() {
        return retrieveTradeSignalsFromFile(hitTpSignalFile());
    }

    public static synchronized void saveSignal(TradeSignalDTO signal) {
        saveToFile(signalFile(), Collections.singletonList(signal));
    }

    public static synchronized void removeTradeSignals(List<TradeSignalDTO> signalsToRemove) {
        Path path = signalFile();

        try {
            List<TradeSignalDTO> previousSignals = retrieveTradeSignalsFromFile(path);
            List<String> idsToRemove = signalsToRemove.stream()
                    .map(DataUtils::normalizeSignal)
                    .map(TradeSignalDTO::getId)
                    .toList();
            List<TradeSignalDTO> remainingSignals = previousSignals.stream()
                    .filter(signal -> !idsToRemove.contains(signal.getId()))
                    .toList();

            writeSignals(path, remainingSignals);
        } catch (RuntimeException e) {
            log.error("Failed to remove trade signals from {}", path, e);
            throw e;
        }
    }

    public static synchronized void saveHitTpSignals(List<TradeSignalDTO> signals) {
        saveToFile(hitTpSignalFile(), signals);
    }

    public static synchronized void saveHitSlSignals(List<TradeSignalDTO> signals) {
        saveToFile(hitSlSignalFile(), signals);
    }

    public static synchronized long loadLastAnalyzedCandleEndTimestamp(String symbol, String timeframe) {
        Path path = analyzedCandlesFile();

        try {
            Map<String, Long> analyzedCandles = readAnalyzedCandles(path);
            return analyzedCandles.getOrDefault(analyzedCandleKey(symbol, timeframe), 0L);
        } catch (RuntimeException e) {
            log.error("Failed to load analyzed-candle state from {}", path, e);
            throw e;
        }
    }

    public static synchronized void saveLastAnalyzedCandleEndTimestamp(String symbol, String timeframe,
                                                                       long candleEndTimestamp) {
        if (candleEndTimestamp <= 0) {
            throw new IllegalArgumentException("Analyzed candle timestamp must be positive");
        }

        Path path = analyzedCandlesFile();

        try {
            Map<String, Long> analyzedCandles = readAnalyzedCandles(path);
            analyzedCandles.put(analyzedCandleKey(symbol, timeframe), candleEndTimestamp);
            writeAnalyzedCandles(path, analyzedCandles);
        } catch (RuntimeException e) {
            log.error("Failed to save analyzed-candle state to {}", path, e);
            throw e;
        }
    }

    private static void saveToFile(Path path, List<TradeSignalDTO> signals) {
        try {
            List<TradeSignalDTO> previousSignals = retrieveTradeSignalsFromFile(path);
            Map<String, TradeSignalDTO> signalsById = previousSignals.stream()
                    .map(DataUtils::normalizeSignal)
                    .collect(Collectors.toMap(
                            TradeSignalDTO::getId,
                            signal -> signal,
                            (oldValue, newValue) -> newValue,
                            LinkedHashMap::new
                    ));

            for (TradeSignalDTO signal : signals) {
                TradeSignalDTO normalizedSignal = normalizeSignal(signal);
                signalsById.put(normalizedSignal.getId(), normalizedSignal);
            }

            writeSignals(path, new ArrayList<>(signalsById.values()));
        } catch (RuntimeException e) {
            log.error("Failed to save trade signals to {}", path, e);
            throw e;
        }
    }

    private static List<TradeSignalDTO> retrieveTradeSignalsFromFile(Path path) {
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }

        try {
            List<TradeSignalDTO> signals = mapper.readValue(path.toFile(), SIGNAL_LIST_TYPE);

            if (signals == null) {
                return new ArrayList<>();
            }

            return signals.stream()
                    .map(DataUtils::normalizeSignal)
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException | RuntimeException e) {
            log.error("Failed to read trade signals from {}", path, e);
            throw new PersistenceException("Failed to read trade signals from " + path, e);
        }
    }

    private static void writeSignals(Path path, List<TradeSignalDTO> signals) {
        writeJson(path, signals, "trade signals");
    }

    private static Map<String, Long> readAnalyzedCandles(Path path) {
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }

        try {
            Map<String, Long> analyzedCandles = mapper.readValue(path.toFile(), ANALYZED_CANDLES_TYPE);
            return analyzedCandles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(analyzedCandles);
        } catch (IOException | RuntimeException e) {
            log.error("Failed to read analyzed-candle state from {}", path, e);
            throw new PersistenceException("Failed to read analyzed-candle state from " + path, e);
        }
    }

    private static void writeAnalyzedCandles(Path path, Map<String, Long> analyzedCandles) {
        writeJson(path, analyzedCandles, "analyzed-candle state");
    }

    private static void writeJson(Path path, Object value, String operation) {
        try {
            Files.createDirectories(path.getParent());

            Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), value);
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.error("Failed to write {} to {}", operation, path, e);
            throw new PersistenceException("Failed to write " + operation + " to " + path, e);
        }
    }

    private static TradeSignalDTO normalizeSignal(TradeSignalDTO signal) {
        if (signal == null) {
            throw new IllegalArgumentException("Trade signal cannot be null");
        }

        if (signal.getId() == null || signal.getId().isBlank()) {
            signal.setId(UUID.randomUUID().toString());
        }

        if (signal.getStatus() == null) {
            signal.setStatus(SignalStatus.OPEN);
        }

        if (signal.getActualEntryPrice() == null && signal.getReferenceEntryPrice() != null) {
            signal.setActualEntryPrice(signal.getReferenceEntryPrice());
        }

        if (signal.getDetectedAtTimestamp() <= 0 && signal.getSignalCandleEndTimestamp() > 0) {
            signal.setDetectedAtTimestamp(signal.getSignalCandleEndTimestamp());
        }

        return signal;
    }

    private static String analyzedCandleKey(String symbol, String timeframe) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol cannot be null or blank");
        }

        if (timeframe == null || timeframe.isBlank()) {
            throw new IllegalArgumentException("Timeframe cannot be null or blank");
        }

        return symbol.trim().toLowerCase() + "|" + timeframe.trim().toLowerCase();
    }

    private static Path signalFile() {
        return DATA_DIRECTORY.resolve(SIGNAL_FILE_NAME);
    }

    private static Path hitTpSignalFile() {
        return DATA_DIRECTORY.resolve(HIT_TP_SIGNAL_FILE_NAME);
    }

    private static Path hitSlSignalFile() {
        return DATA_DIRECTORY.resolve(HIT_SL_SIGNAL_FILE_NAME);
    }

    private static Path analyzedCandlesFile() {
        return DATA_DIRECTORY.resolve(ANALYZED_CANDLES_FILE_NAME);
    }

    public static class PersistenceException extends RuntimeException {

        public PersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
