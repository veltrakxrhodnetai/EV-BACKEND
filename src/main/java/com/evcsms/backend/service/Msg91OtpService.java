package com.evcsms.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Service
public class Msg91OtpService {

    private static final Logger logger = LoggerFactory.getLogger(Msg91OtpService.class);
    private static final String DEFAULT_MSG91_AUTH_KEY = "500086Axgw0hbj69e8a5a9P1";
    private static final String DEFAULT_MSG91_TEMPLATE_ID = "69e8c993b6c8931b61090803";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String authKey;
    private final String templateId;
    private final String countryCode;
    private final String endpoint;
    private final String mode;
    private final String flowEndpoint;
    private final String flowOtpVarName;
    private final String chargeCompleteTemplateId;
    private final String refundInitiatedTemplateId;
    private final String refundAmountVarName;

    public Msg91OtpService(
            @Value("${app.auth.otp.msg91.enabled:true}") boolean enabled,
            @Value("${app.auth.otp.msg91.auth-key:}") String authKey,
            @Value("${app.auth.otp.msg91.template-id:}") String templateId,
            @Value("${app.auth.otp.msg91.country-code:91}") String countryCode,
            @Value("${app.auth.otp.msg91.endpoint:https://control.msg91.com/api/v5/otp}") String endpoint,
            @Value("${app.auth.otp.msg91.mode:auto}") String mode,
            @Value("${app.auth.otp.msg91.flow-endpoint:https://control.msg91.com/api/v5/flow}") String flowEndpoint,
                @Value("${app.auth.otp.msg91.flow-otp-var-name:OTP}") String flowOtpVarName,
                @Value("${app.auth.otp.msg91.charge-complete-template-id:69e8c91aec4ab5ed1b08dfe2}") String chargeCompleteTemplateId,
                @Value("${app.auth.otp.msg91.refund-initiated-template-id:6abac058a0b6cd5d20079b82}") String refundInitiatedTemplateId,
                @Value("${app.auth.otp.msg91.refund-amount-var-name:var1}") String refundAmountVarName
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.enabled = enabled;
        this.authKey = firstNonBlank(authKey, DEFAULT_MSG91_AUTH_KEY);
        this.templateId = firstNonBlank(templateId, DEFAULT_MSG91_TEMPLATE_ID);
        this.countryCode = firstNonBlank(countryCode, "91");
        this.endpoint = firstNonBlank(endpoint, "https://control.msg91.com/api/v5/otp");
        this.mode = firstNonBlank(mode, "auto").toLowerCase();
        this.flowEndpoint = firstNonBlank(flowEndpoint, "https://control.msg91.com/api/v5/flow");
        this.flowOtpVarName = firstNonBlank(flowOtpVarName, "OTP");
        this.chargeCompleteTemplateId = firstNonBlank(chargeCompleteTemplateId, "69e8c91aec4ab5ed1b08dfe2");
        this.refundInitiatedTemplateId = firstNonBlank(refundInitiatedTemplateId, "6abac058a0b6cd5d20079b82");
        this.refundAmountVarName = firstNonBlank(refundAmountVarName, "var1");
    }

    @PostConstruct
    void logResolvedConfig() {
        logger.info("MSG91 config resolved: enabled={}, authKeyPresent={}, templateId={}, countryCode={}, endpoint={}, mode={}, flowEndpoint={}, flowOtpVarName={}, chargeCompleteTemplateId={}, refundInitiatedTemplateId={}",
                enabled,
                authKey != null && !authKey.isBlank(),
                templateId,
                countryCode,
            endpoint,
            mode,
            flowEndpoint,
            flowOtpVarName,
            chargeCompleteTemplateId,
            refundInitiatedTemplateId);
    }

    public void sendChargeCompleteMessage(String phoneNumber) {
        sendTransactionalSms("charge completion", chargeCompleteTemplateId, phoneNumber, Map.of());
    }

    /**
     * Notifies the customer that a refund has been initiated. Template text:
     * "Thank you for choosing Veltrak! Your refund amount ##var1## has been initiated and will be
     * credited to your original payment account once processing is complete."
     */
    public void sendRefundInitiatedMessage(String phoneNumber, double refundAmount) {
        String formattedAmount = "Rs. " + java.math.BigDecimal.valueOf(refundAmount)
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .toPlainString();
        sendTransactionalSms("refund initiated", refundInitiatedTemplateId, phoneNumber,
                Map.of(refundAmountVarName, formattedAmount));
    }

    private void sendTransactionalSms(String label, String flowTemplateId, String phoneNumber, Map<String, String> variables) {
        if (!enabled || authKey == null || authKey.isBlank() || flowTemplateId == null || flowTemplateId.isBlank()) {
            logger.warn("Skipping {} SMS due to missing MSG91 configuration", label);
            return;
        }

        String normalizedPhone = normalizePhone(phoneNumber);
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            logger.warn("Skipping {} SMS due to empty phone number", label);
            return;
        }

        String mobile = countryCode + normalizedPhone;

        try {
            Map<String, Object> recipient = new java.util.LinkedHashMap<>();
            recipient.put("mobiles", mobile);
            recipient.putAll(variables);

            Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
            requestBody.put("template_id", flowTemplateId);
            requestBody.put("short_url", "0");
            requestBody.put("realTimeResponse", "1");
            requestBody.put("recipients", java.util.List.of(recipient));

            HttpRequest request = HttpRequest.newBuilder(URI.create(flowEndpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("accept", "application/json")
                    .header("content-type", "application/json")
                    .header("authkey", authKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.error("MSG91 {} SMS failed with HTTP {}: {}", label, response.statusCode(), compactMessage(response.body()));
                return;
            }

            Map<String, Object> payload = objectMapper.readValue(response.body(), Map.class);
            Object type = payload.get("type");
            if (type != null && "success".equalsIgnoreCase(String.valueOf(type))) {
                logger.info("MSG91 {} SMS sent successfully to +{}{}", label, countryCode, normalizedPhone);
                return;
            }

            logger.error("MSG91 {} SMS rejected: {}", label, compactMessage(response.body()));
        } catch (Exception ex) {
            logger.error("MSG91 {} SMS error for phone {}: {}", label, phoneNumber, ex.getMessage(), ex);
        }
    }

    public SmsSendResult sendOtp(String phoneNumber, String otp) {
        if (!enabled) {
            String message = "MSG91 OTP is disabled by configuration";
            logger.warn(message);
            return new SmsSendResult(false, message);
        }

        if (authKey == null || authKey.isBlank() || templateId == null || templateId.isBlank()) {
            String message = "MSG91 auth key or template id is missing";
            logger.error(message);
            return new SmsSendResult(false, message);
        }

        String normalizedPhone = normalizePhone(phoneNumber);
        String mobile = countryCode + normalizedPhone;

        try {
            SmsSendResult result;
            if ("flow".equalsIgnoreCase(mode)) {
                result = sendViaFlowApi(mobile, otp, normalizedPhone);
            } else if ("otp".equalsIgnoreCase(mode)) {
                result = sendViaOtpApi(mobile, otp, normalizedPhone);
            } else {
                // auto mode: try OTP API first, and if template is incompatible, fallback to Flow API.
                result = sendViaOtpApi(mobile, otp, normalizedPhone);
                if (!result.success() && isTemplateInvalidMessage(result.message())) {
                    logger.warn("MSG91 OTP API template mismatch detected, retrying using Flow API");
                    result = sendViaFlowApi(mobile, otp, normalizedPhone);
                }
            }

            return result;
        } catch (Exception ex) {
            String message = "MSG91 OTP error: " + ex.getMessage();
            logger.error("{} for phone {}", message, phoneNumber, ex);
            return new SmsSendResult(false, message);
        }
    }

    private SmsSendResult sendViaOtpApi(String mobile, String otp, String normalizedPhone) throws Exception {
        String query = "template_id=" + urlEncode(templateId)
                + "&mobile=" + urlEncode(mobile)
                + "&otp=" + urlEncode(otp);
        URI uri = URI.create(endpoint + "?" + query);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .header("authkey", authKey)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = "MSG91 OTP API failed with HTTP " + response.statusCode() + ": " + compactMessage(response.body());
            logger.error(message);
            return new SmsSendResult(false, message);
        }

        Map<String, Object> payload = objectMapper.readValue(response.body(), Map.class);
        Object type = payload.get("type");
        if (type != null && "success".equalsIgnoreCase(String.valueOf(type))) {
            logger.info("MSG91 OTP API sent successfully to +{}{}", countryCode, normalizedPhone);
            return new SmsSendResult(true, "OTP sent successfully");
        }

        String message = "MSG91 OTP API rejected: " + compactMessage(response.body());
        logger.error(message);
        return new SmsSendResult(false, message);
    }

    private SmsSendResult sendViaFlowApi(String mobile, String otp, String normalizedPhone) throws Exception {
        Map<String, Object> recipient = new java.util.LinkedHashMap<>();
        recipient.put("mobiles", mobile);
        recipient.put(flowOtpVarName, otp);
        // Send common aliases so templates using OTP or VAR1 can still resolve.
        if (!"OTP".equalsIgnoreCase(flowOtpVarName)) {
            recipient.put("OTP", otp);
        }
        if (!"VAR1".equalsIgnoreCase(flowOtpVarName)) {
            recipient.put("VAR1", otp);
        }

        Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
        requestBody.put("template_id", templateId);
        requestBody.put("short_url", "0");
        requestBody.put("realTimeResponse", "1");
        requestBody.put("recipients", java.util.List.of(recipient));

        String body = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder(URI.create(flowEndpoint))
                .timeout(Duration.ofSeconds(15))
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .header("authkey", authKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = "MSG91 Flow API failed with HTTP " + response.statusCode() + ": " + compactMessage(response.body());
            logger.error(message);
            return new SmsSendResult(false, message);
        }

        Map<String, Object> payload = objectMapper.readValue(response.body(), Map.class);
        Object type = payload.get("type");
        if (type != null && "success".equalsIgnoreCase(String.valueOf(type))) {
            logger.info("MSG91 Flow API sent successfully to +{}{}", countryCode, normalizedPhone);
            return new SmsSendResult(true, "OTP sent successfully");
        }

        String message = "MSG91 Flow API rejected: " + compactMessage(response.body());
        logger.error(message);
        return new SmsSendResult(false, message);
    }

    private boolean isTemplateInvalidMessage(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("template id missing") || normalized.contains("invalid template");
    }

    private String normalizePhone(String phoneNumber) {
        String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }
        return digits;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String firstNonBlank(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private String compactMessage(String value) {
        if (value == null) {
            return "No response body";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    public record SmsSendResult(boolean success, String message) {
    }
}
