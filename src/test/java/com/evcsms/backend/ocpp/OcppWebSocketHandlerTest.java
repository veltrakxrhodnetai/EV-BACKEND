package com.evcsms.backend.ocpp;

import com.evcsms.backend.model.Charger;
import com.evcsms.backend.repository.ChargerRepository;
import com.evcsms.backend.repository.ChargingSessionRepository;
import com.evcsms.backend.repository.ConnectorRepository;
import com.evcsms.backend.repository.MeterValueRepository;
import com.evcsms.backend.repository.OcppConfigurationRepository;
import com.evcsms.backend.repository.OcppMessageLogRepository;
import com.evcsms.backend.repository.TariffRepository;
import com.evcsms.backend.service.Msg91OtpService;
import com.evcsms.backend.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the WebSocket-lifetime bugs found investigating the ~60-minute charger disconnect issue:
 * duplicate/overlapping session registration, keepalive ping scheduling, OCPP Heartbeat handling,
 * and the RJPM-002 charger identity resolution bug.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OcppWebSocketHandlerTest {

    @Mock private ChargerRepository chargerRepository;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private ChargingSessionRepository chargingSessionRepository;
    @Mock private TariffRepository tariffRepository;
    @Mock private MeterValueRepository meterValueRepository;
    @Mock private OcppConfigurationRepository ocppConfigurationRepository;
    @Mock private OcppMessageLogRepository ocppMessageLogRepository;
    @Mock private Msg91OtpService msg91OtpService;
    @Mock private PaymentService paymentService;

    private OcppWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OcppWebSocketHandler(
                new ObjectMapper(),
                chargerRepository,
                connectorRepository,
                chargingSessionRepository,
                tariffRepository,
                meterValueRepository,
                ocppConfigurationRepository,
                ocppMessageLogRepository,
                msg91OtpService,
                paymentService
        );
    }

    private WebSocketSession mockSession(String chargerId, String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn(sessionId);
        lenient().when(session.getUri())
                .thenReturn(URI.create("ws://cms.example.com/ws/ocpp/1.6J/1/" + chargerId));
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getRemoteAddress()).thenReturn(new InetSocketAddress("10.0.0.1", 5555));
        lenient().when(session.getAcceptedProtocol()).thenReturn("ocpp1.6");
        lenient().when(session.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        return session;
    }

    private Charger charger(long id, String ocppIdentity) {
        Charger charger = new Charger();
        charger.setId(id);
        charger.setOcppIdentity(ocppIdentity);
        charger.setStatus("Available");
        return charger;
    }

    @SuppressWarnings("unchecked")
    private Map<String, WebSocketSession> activeSessions() throws Exception {
        Field field = OcppWebSocketHandler.class.getDeclaredField("activeSessions");
        field.setAccessible(true);
        return (Map<String, WebSocketSession>) field.get(handler);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> connectionDiagnostics() throws Exception {
        Field field = OcppWebSocketHandler.class.getDeclaredField("connectionDiagnostics");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(handler);
    }

    private TextMessage ocppCall(String msgId, String action, String jsonPayload) {
        return new TextMessage("[2,\"" + msgId + "\",\"" + action + "\"," + jsonPayload + "]");
    }

    // --- Investigation 4: duplicate/overlapping session race ------------------------------------

    @Test
    void newSessionForSameChargerReplacesOldAndClosesIt() throws Exception {
        when(chargerRepository.findByOcppIdentity("IN-VELTRAK-RJPM-002"))
                .thenReturn(Optional.of(charger(2L, "IN-VELTRAK-RJPM-002")));

        WebSocketSession sessionA = mockSession("IN-VELTRAK-RJPM-002", "session-A");
        WebSocketSession sessionB = mockSession("IN-VELTRAK-RJPM-002", "session-B");

        handler.afterConnectionEstablished(sessionA);
        assertEquals(1, handler.getActiveSessionCount());

        handler.afterConnectionEstablished(sessionB);

        // Still exactly one registered session for this chargerId — the new one — not two.
        assertEquals(1, handler.getActiveSessionCount());
        assertEquals("session-B", activeSessions().get("IN-VELTRAK-RJPM-002").getId());

        // The superseded session must be proactively closed rather than left as a zombie.
        verify(sessionA).close(any(CloseStatus.class));
    }

    @Test
    void oldSessionClosingAfterReplacementDoesNotUnregisterNewSession() throws Exception {
        when(chargerRepository.findByOcppIdentity("IN-VELTRAK-RJPM-002"))
                .thenReturn(Optional.of(charger(2L, "IN-VELTRAK-RJPM-002")));

        WebSocketSession sessionA = mockSession("IN-VELTRAK-RJPM-002", "session-A");
        WebSocketSession sessionB = mockSession("IN-VELTRAK-RJPM-002", "session-B");

        handler.afterConnectionEstablished(sessionA);
        handler.afterConnectionEstablished(sessionB);

        // Simulate the underlying container finally delivering the stale close callback for A,
        // sometime after B has already taken over — this is exactly what the production log showed.
        handler.afterConnectionClosed(sessionA, new CloseStatus(1006, null));

        assertEquals(1, handler.getActiveSessionCount());
        assertEquals("session-B", activeSessions().get("IN-VELTRAK-RJPM-002").getId());
    }

    @Test
    void multipleSimultaneousChargerConnectionsAreIndependent() throws Exception {
        when(chargerRepository.findByOcppIdentity("IN-VELTRAK-CHNN-001"))
                .thenReturn(Optional.of(charger(1L, "IN-VELTRAK-CHNN-001")));
        when(chargerRepository.findByOcppIdentity("IN-VELTRAK-RJPM-002"))
                .thenReturn(Optional.of(charger(2L, "IN-VELTRAK-RJPM-002")));

        WebSocketSession chnn = mockSession("IN-VELTRAK-CHNN-001", "chnn-session");
        WebSocketSession rjpm = mockSession("IN-VELTRAK-RJPM-002", "rjpm-session");

        handler.afterConnectionEstablished(chnn);
        handler.afterConnectionEstablished(rjpm);
        assertEquals(2, handler.getActiveSessionCount());

        handler.afterConnectionClosed(chnn, CloseStatus.NORMAL);

        assertEquals(1, handler.getActiveSessionCount());
        assertEquals("rjpm-session", activeSessions().get("IN-VELTRAK-RJPM-002").getId());
    }

    @Test
    void connectionDiagnosticsAreCleanedUpOnClose() throws Exception {
        when(chargerRepository.findByOcppIdentity("IN-VELTRAK-CHNN-001"))
                .thenReturn(Optional.of(charger(1L, "IN-VELTRAK-CHNN-001")));

        WebSocketSession session = mockSession("IN-VELTRAK-CHNN-001", "chnn-session");
        handler.afterConnectionEstablished(session);
        assertEquals(1, connectionDiagnostics().size());

        handler.afterConnectionClosed(session, new CloseStatus(1006, null));
        assertEquals(0, connectionDiagnostics().size());
    }

    // --- Investigation 2/3: ping keepalive + OCPP Heartbeat --------------------------------------

    @Test
    void keepAlivePingIsSentToOpenSessionsAndSkipsDeadOnes() throws Exception {
        when(chargerRepository.findByOcppIdentity(anyString())).thenReturn(Optional.empty());

        WebSocketSession openSession = mockSession("IN-VELTRAK-CHNN-001", "open-session");
        WebSocketSession deadSession = mockSession("IN-VELTRAK-RJPM-002", "dead-session");
        when(deadSession.isOpen()).thenReturn(false);

        handler.afterConnectionEstablished(openSession);
        handler.afterConnectionEstablished(deadSession);

        handler.sendKeepAlivePings();

        verify(openSession).sendMessage(any(PingMessage.class));
        verify(deadSession, never()).sendMessage(any(PingMessage.class));
    }

    @Test
    void heartbeatIsAcknowledgedAndUpdatesChargerPresence() throws Exception {
        Charger charger = charger(1L, "IN-VELTRAK-CHNN-001");
        when(chargerRepository.findByOcppIdentity("IN-VELTRAK-CHNN-001")).thenReturn(Optional.of(charger));

        WebSocketSession session = mockSession("IN-VELTRAK-CHNN-001", "chnn-session");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, ocppCall("hb-1", "Heartbeat", "{}"));

        ArgumentCaptor<WebSocketMessage<?>> sentCaptor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(sentCaptor.capture());

        boolean sawCallResultForHeartbeat = sentCaptor.getAllValues().stream()
                .filter(m -> m instanceof TextMessage)
                .map(m -> ((TextMessage) m).getPayload())
                .anyMatch(payload -> payload.startsWith("[3,\"hb-1\""));
        assertTrue(sawCallResultForHeartbeat, "expected a CallResult response to the Heartbeat");

        assertEquals("ONLINE", charger.getCommunicationStatus());
        assertNotNull(charger.getLastHeartbeat());
    }

    // --- Investigation 5: RJPM-002 charger identity resolution -----------------------------------

    @Test
    void statusNotificationResolvesChargerViaNormalizedFallbackWhenExactMatchMisses() throws Exception {
        // Simulate the real production data mismatch: the DB row's stored identity differs from the
        // URL-derived identity only by whitespace/case, so the exact-match lookup misses.
        Charger storedCharger = charger(2L, " IN-VELTRAK-RJPM-002 ");
        when(chargerRepository.findByOcppIdentity("IN-VELTRAK-RJPM-002")).thenReturn(Optional.empty());
        when(chargerRepository.findByOcppIdentityNormalized("IN-VELTRAK-RJPM-002"))
                .thenReturn(Optional.of(storedCharger));

        WebSocketSession session = mockSession("IN-VELTRAK-RJPM-002", "rjpm-session");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, ocppCall("13", "StatusNotification",
                "{\"connectorId\":0,\"status\":\"Available\",\"errorCode\":\"NoError\"}"));

        ArgumentCaptor<WebSocketMessage<?>> sentCaptor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(sentCaptor.capture());

        boolean sawCallError = sentCaptor.getAllValues().stream()
                .filter(m -> m instanceof TextMessage)
                .map(m -> ((TextMessage) m).getPayload())
                .anyMatch(payload -> payload.startsWith("[4,"));
        assertFalse(sawCallError, "StatusNotification must not produce a CallError once identity resolves");

        boolean sawCallResult = sentCaptor.getAllValues().stream()
                .filter(m -> m instanceof TextMessage)
                .map(m -> ((TextMessage) m).getPayload())
                .anyMatch(payload -> payload.startsWith("[3,\"13\""));
        assertTrue(sawCallResult, "expected a normal CallResult for StatusNotification");

        assertEquals("Available", storedCharger.getStatus());
        verify(chargerRepository).findByOcppIdentityNormalized("IN-VELTRAK-RJPM-002");
    }

    @Test
    void statusNotificationStillFailsControlledWhenChargerTrulyUnknown() throws Exception {
        when(chargerRepository.findByOcppIdentity("UNKNOWN-CHARGER")).thenReturn(Optional.empty());
        when(chargerRepository.findByOcppIdentityNormalized("UNKNOWN-CHARGER")).thenReturn(Optional.empty());

        WebSocketSession session = mockSession("UNKNOWN-CHARGER", "unknown-session");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, ocppCall("13", "StatusNotification",
                "{\"connectorId\":0,\"status\":\"Available\",\"errorCode\":\"NoError\"}"));

        ArgumentCaptor<WebSocketMessage<?>> sentCaptor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(sentCaptor.capture());

        boolean sawCallError = sentCaptor.getAllValues().stream()
                .filter(m -> m instanceof TextMessage)
                .map(m -> ((TextMessage) m).getPayload())
                .anyMatch(payload -> payload.startsWith("[4,\"13\""));
        assertTrue(sawCallError, "a genuinely unknown charger must still get a controlled CallError, not a dropped connection");
    }
}
