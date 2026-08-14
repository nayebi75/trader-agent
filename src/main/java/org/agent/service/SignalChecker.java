package org.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.agent.client.ExchangeClient;
import org.agent.service.dto.CandleDTO;
import org.agent.service.dto.TradeSignalDTO;
import org.agent.utils.DataUtils;
import org.agent.utils.TradeUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
public class SignalChecker implements Runnable {

    private final ExchangeClient exchangeClient = new ExchangeClient();

    @Override
    public void run() {
        log.info("enter to signal checker");
        try {
            validateSignals();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        log.info("exit from signal checker");
    }

    public void validateSignals() {
        log.info("enter to validateSignals");
        List<TradeSignalDTO> signals = DataUtils.loadTradeSignals();
        signals.forEach(this::checkSignal);
        List<TradeSignalDTO> hitTpSignals = signals.stream().filter(TradeSignalDTO::isHitTp).toList();
        List<TradeSignalDTO> hitSlSignals = signals.stream().filter(TradeSignalDTO::isHitSL).toList();
        if (!hitTpSignals.isEmpty()) {
            DataUtils.saveHitTpSignals(hitTpSignals);
            DataUtils.removeTradeSignals(hitTpSignals);
        }
        if (!hitSlSignals.isEmpty()) {
            DataUtils.saveHitSlSignals(hitSlSignals);
            DataUtils.removeTradeSignals(hitSlSignals);
        }
        log.info("-> total:{}, hitNone: {}, hitTp: {}, hitSl: {}",
                signals.size() + hitTpSignals.size() + hitSlSignals.size(),
                signals.size() - (hitTpSignals.size() + hitSlSignals.size()),
                hitTpSignals.size(),
                hitSlSignals.size());
    }

    @SuppressWarnings("all")
    private void checkSignal(TradeSignalDTO tradeSignalDTO) {
        try {
            int size = TradeUtils.calculateHoursUntilNow(tradeSignalDTO.getTimestamp());
            if (size > 0) {
                size = Math.min(size, 250);
                List<CandleDTO> candleDTOS = exchangeClient.fetchHourlyClosingPrices(tradeSignalDTO.getSymbol(), 1, size);
                candleDTOS = candleDTOS.stream().sorted(Comparator.comparingLong(CandleDTO::getTimestamp)).toList();

                Optional<CandleDTO> firstHitTp = candleDTOS.stream().filter(candleDTO -> candleDTO.getHigh() >= tradeSignalDTO.getTakeProfit()).findFirst();
                Optional<CandleDTO> firstHitSl = candleDTOS.stream().filter(candleDTO -> candleDTO.getLow() <= tradeSignalDTO.getStopLoss()).findFirst();

                if (firstHitTp.isPresent() && firstHitSl.isEmpty()) tradeSignalDTO.setHitTp(true);
                if (firstHitSl.isPresent() && firstHitTp.isEmpty()) tradeSignalDTO.setHitSL(true);
                if (firstHitTp.isPresent() && firstHitSl.isPresent()) {
                    if (firstHitTp.get().getTimestamp() < firstHitSl.get().getTimestamp())
                        tradeSignalDTO.setHitTp(true);
                    if (firstHitSl.get().getTimestamp() < firstHitTp.get().getTimestamp())
                        tradeSignalDTO.setHitSL(true);
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }
}