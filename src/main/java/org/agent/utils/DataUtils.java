package org.agent.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.agent.service.dto.TradeSignalDTO;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

@Slf4j
public class DataUtils {

    static final File SIGNAL_FILE = new File("data/signals.json");
    static final File HIT_TP_SIGNAL_FILE = new File("data/hitTpSignals.json");
    static final File HIT_SL_SIGNAL_FILE = new File("data/hitSlSignals.json");

    static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module());

    public static synchronized List<TradeSignalDTO> loadTradeSignals() {
        return retrieveTradeSignalsFromFile(SIGNAL_FILE);
    }

    public static synchronized List<TradeSignalDTO> loadHitTpTradeSignals() {
        return retrieveTradeSignalsFromFile(HIT_TP_SIGNAL_FILE);
    }

    public static synchronized void saveSignal(TradeSignalDTO signal) {
        saveToFile(SIGNAL_FILE, Collections.singletonList(signal));
    }

    public static synchronized void removeTradeSignals(List<TradeSignalDTO> signalsToRemove) {
        File file = SIGNAL_FILE;
        try {
            Set<TradeSignalDTO> signalDTOS = new HashSet<>();
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } else {
                try {
                    String json = Files.readString(file.toPath());
                    List<TradeSignalDTO> prevSignals = mapper.readValue(json, new TypeReference<List<TradeSignalDTO>>() {
                    });
                    List<Long> signalIds = signalsToRemove.stream().map(TradeSignalDTO::getTimestamp).toList();
                    signalDTOS.addAll(prevSignals.stream().filter(s -> !signalIds.contains(s.getTimestamp())).toList());
                } catch (Exception ignored) {
                }
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, signalDTOS);
        } catch (Exception ignored) {
        }
    }

    public static synchronized void saveHitTpSignals(List<TradeSignalDTO> signals) {
        saveToFile(HIT_TP_SIGNAL_FILE, signals);
    }

    public static synchronized void saveHitSlSignals(List<TradeSignalDTO> signals) {
        saveToFile(HIT_SL_SIGNAL_FILE, signals);
    }

    private static void saveToFile(File file, List<TradeSignalDTO> signals) {
        try {
            Set<TradeSignalDTO> signalDTOS = new HashSet<>();
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } else {
                try {
                    String json = Files.readString(file.toPath());
                    List<TradeSignalDTO> prevSignals = mapper.readValue(json, new TypeReference<List<TradeSignalDTO>>() {
                    });
                    signalDTOS.addAll(prevSignals);
                } catch (Exception ignored) {
                }
            }
            signalDTOS.addAll(signals);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, signalDTOS);
        } catch (Exception ignored) {
        }
    }

    private static List<TradeSignalDTO> retrieveTradeSignalsFromFile(File file) {
        List<TradeSignalDTO> tradeSignalDTOS = new ArrayList<>();
        try {
            if (file.exists()) {

                String json = Files.readString(file.toPath());
                tradeSignalDTOS = mapper.readValue(json, new TypeReference<List<TradeSignalDTO>>() {
                });
            }
        } catch (Exception ignored) {
        }
        return tradeSignalDTOS;
    }
}
