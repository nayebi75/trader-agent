package org.agent.client;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;
import org.agent.utils.TradeUtils;
import org.json.JSONArray;
import org.agent.service.dto.CandleDTO;
import org.agent.service.dto.CryptoCurrencyDTO;
import org.agent.service.dto.LBankAvailableCryptoCurrenciesDTO;
import org.agent.service.dto.LBankTickerDTO;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ExchangeClient {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<CryptoCurrencyDTO> getAvailableCryptoCurrencies() {
        List<CryptoCurrencyDTO> cryptoCurrencies = new ArrayList<>();
        List<LBankAvailableCryptoCurrenciesDTO> availableCryptoCurrencies = getCryptoCurrenciesFromLBank();
        availableCryptoCurrencies.forEach(availableCryptoCurrency -> {
            CryptoCurrencyDTO cryptoCurrencyDTO = new CryptoCurrencyDTO();
            cryptoCurrencyDTO.setSymbol(availableCryptoCurrency.getSymbol());
            cryptoCurrencyDTO.setTimestamp(availableCryptoCurrency.getTimestamp());
            cryptoCurrencyDTO.setChange(availableCryptoCurrency.getTicker().getChange());
            cryptoCurrencyDTO.setHigh(availableCryptoCurrency.getTicker().getHigh());
            cryptoCurrencyDTO.setLow(availableCryptoCurrency.getTicker().getLow());
            cryptoCurrencyDTO.setLatest(availableCryptoCurrency.getTicker().getLatest());
            cryptoCurrencyDTO.setVol(availableCryptoCurrency.getTicker().getVol());
            cryptoCurrencyDTO.setTurnover(availableCryptoCurrency.getTicker().getTurnover());
            cryptoCurrencies.add(cryptoCurrencyDTO);
        });
        return cryptoCurrencies;
    }

    private List<LBankAvailableCryptoCurrenciesDTO> getCryptoCurrenciesFromLBank() {
        log.info("enter to getCryptoCurrenciesFromLBank");
        List<LBankAvailableCryptoCurrenciesDTO> availableCryptoCurrencies = new ArrayList<>();
        try {
            StringBuilder url = new StringBuilder("https://api.lbank.info/v2/ticker/24hr.do?symbol=all");
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(new URI(url.toString())).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode jsonNode = objectMapper.readTree(response.body());
            ArrayNode arrayNode = (ArrayNode) jsonNode.get("data");
            for (JsonNode node : arrayNode) {
                String symbol = node.get("symbol").asText();
                LBankAvailableCryptoCurrenciesDTO model = new LBankAvailableCryptoCurrenciesDTO();
                model.setSymbol(symbol);
                LBankTickerDTO lBankTickerDTO = new LBankTickerDTO();
                lBankTickerDTO.setChange(node.get("ticker").get("change").asText());
                lBankTickerDTO.setHigh(node.get("ticker").get("high").asText());
                lBankTickerDTO.setLow(node.get("ticker").get("low").asText());
                lBankTickerDTO.setVol(node.get("ticker").get("vol").asText());
                lBankTickerDTO.setTurnover(node.get("ticker").get("turnover").asText());
                lBankTickerDTO.setLatest(node.get("ticker").get("latest").asText());
                model.setTicker(lBankTickerDTO);
                availableCryptoCurrencies.add(model);
            }
        } catch (Exception e) {
            log.error(e.getMessage());
        }
        log.info("fetched cryptoCurrencies with size: {}", availableCryptoCurrencies.size());
        return availableCryptoCurrencies;
    }

    public List<CandleDTO> fetchMinutelyClosingPricesWithTimestamp(String cryptoCurrency, int minutesInterval, int size, long timestamp) {
        String type = "minute" + minutesInterval;
        return internalFetchClosingPrices(cryptoCurrency, type, size, timestamp);
    }

    public List<CandleDTO> fetchMinutelyClosingPrices(String cryptoCurrency, int minutesInterval, int size) {
        String type = "minute" + minutesInterval;
        long roundedTimestamp = TradeUtils.getMinuteTimestamp(minutesInterval, size);
        return internalFetchClosingPrices(cryptoCurrency, type, size, roundedTimestamp);
    }

    public List<CandleDTO> fetchHourlyClosingPrices(String cryptoCurrency, int hoursInterval, int size) {
        String type = "hour" + hoursInterval;
        long roundedTimestamp = TradeUtils.getHourTimestamp(hoursInterval, size);
        return internalFetchClosingPrices(cryptoCurrency, type, size, roundedTimestamp);
    }

    public List<CandleDTO> fetchDailyClosingPrices(String cryptoCurrency, int daysInterval, int size) {
        String type = "day" + daysInterval;
        long timestamp = TradeUtils.getDayTimestamp(daysInterval, size);
        return internalFetchClosingPrices(cryptoCurrency, type, size, timestamp);
    }

    /**
     * @implNote minute1：1 minute
     * minute5：5 minutes
     * minute15：15minutes
     * minute30：30 minutes
     * hour1：1 hour
     * hour4：4 hours
     * hour8：8 hours
     * hour12：12 hours
     * day1：1 day
     * week1：1 week
     * month1：1 month
     */
    private List<CandleDTO> internalFetchClosingPrices(String cryptoCurrency, String type, int size, Long timestamp) {
        List<CandleDTO> candleDTOS = new ArrayList<>();
        try {
            StringBuilder url = new StringBuilder("https://api.lbkex.com/v1/kline.do?");
            url.append("symbol=").append(cryptoCurrency);
            url.append("&size=").append(size);
            url.append("&type=").append(type);
            url.append("&time=").append(timestamp);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(new URI(url.toString())).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONArray jsonArray = new JSONArray(response.body());
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONArray row = jsonArray.getJSONArray(i);
                CandleDTO candleDTO = CandleDTO.builder()
                        .timestamp(row.getLong(0)) // timestamp
                        .open(row.getDouble(1)) // open
                        .high(row.getDouble(2)) // high
                        .low(row.getDouble(3)) // low
                        .close(row.getDouble(4)) // close
                        .volume(row.getDouble(5))  // volume
                        .build();
                candleDTOS.add(candleDTO);
            }
            log.trace("fetch closing prices of cryptoCurrency: {} with size of:{}", cryptoCurrency, candleDTOS.size());
        } catch (Exception e) {
            log.error("error in internalFetchClosingPrices: {}", e.getMessage(), e);
        }
        return candleDTOS;
    }

}
