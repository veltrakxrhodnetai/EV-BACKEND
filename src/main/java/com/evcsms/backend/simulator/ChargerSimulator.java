package com.evcsms.backend.simulator;

import com.evcsms.backend.util.IdUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

public class ChargerSimulator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String chargerId = args.length > 1 ? args[1] : "SIM-CHARGER-001";
        String endpoint = args.length > 0
            ? args[0]
            : "ws://localhost:8080/ws/ocpp/1.6/SIM-STATION/" + chargerId;
        int meterSamples = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        long intervalMillis = args.length > 3 ? Long.parseLong(args[3]) : 2000L;

        System.out.println("Starting ChargerSimulator...");
        System.out.printf("Endpoint=%s, chargerId=%s, meterSamples=%d, intervalMillis=%d%n",
                endpoint, chargerId, meterSamples, intervalMillis);

        CountDownLatch closedLatch = new CountDownLatch(1);
        WebSocket webSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(endpoint), new ConsoleWebSocketListener(closedLatch))
                .join();

        String transactionId = IdUtils.generateTransactionId();
        double meterStart = 10000.0;

        sendAction(webSocket, chargerId, "BootNotification", Map.of(
                "chargePointVendor", "Belectriq",
                "chargePointModel", "BQ-DC120",
                "chargePointSerialNumber", chargerId,
                "firmwareVersion", "sim-1.0.0"
        ));

        Thread.sleep(1000L);

        sendAction(webSocket, chargerId, "StartTransaction", Map.of(
                "transactionId", transactionId,
                "connectorId", 1,
                "idTag", "RFID-SIM-001",
                "meterStart", meterStart,
                "timestamp", Instant.now().toString()
        ));

        for (int index = 1; index <= meterSamples; index++) {
            Thread.sleep(intervalMillis);
            double meterValue = meterStart + (index * 250.0);

            sendAction(webSocket, chargerId, "MeterValues", Map.of(
                    "transactionId", transactionId,
                    "connectorId", 1,
                    "meterValue", meterValue,
                    "powerKw", 30.5,
                    "voltage", 400.0,
                    "current", 75.0,
                    "timestamp", Instant.now().toString()
            ));
        }

        Thread.sleep(intervalMillis);

        sendAction(webSocket, chargerId, "StopTransaction", Map.of(
                "transactionId", transactionId,
                "meterStop", meterStart + (meterSamples * 250.0),
                "reason", "Local",
                "timestamp", Instant.now().toString()
        ));

        Thread.sleep(1000L);
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "simulation-complete").join();
        closedLatch.await();

        System.out.println("Charger simulation complete.");
    }

    private static void sendAction(WebSocket webSocket,
                                   String chargerId,
                                   String action,
                                   Map<String, Object> payload) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("action", action);
        envelope.put("chargerId", chargerId);
        envelope.put("payload", payload);

        String json = OBJECT_MAPPER.writeValueAsString(envelope);
        System.out.printf("Sending %s: %s%n", action, json);
        webSocket.sendText(json, true).join();
    }

    private static class ConsoleWebSocketListener implements WebSocket.Listener {

        private final CountDownLatch closedLatch;

        private ConsoleWebSocketListener(CountDownLatch closedLatch) {
            this.closedLatch = closedLatch;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            System.out.println("WebSocket connected.");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            System.out.println("Received: " + data);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.printf("WebSocket closed. status=%d, reason=%s%n", statusCode, reason);
            closedLatch.countDown();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("WebSocket error: " + error.getMessage());
            closedLatch.countDown();
        }
    }
}
