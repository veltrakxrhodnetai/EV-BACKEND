package com.evcsms.backend.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Aspect
@Component
public class AuditAspect {

    private static final Logger logger = LoggerFactory.getLogger(AuditAspect.class);
    private static final int MAX_REQUEST_SUMMARY_LENGTH = 700;

    private final ObjectMapper objectMapper;

    public AuditAspect(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(com.evcsms.backend.audit.Audit)")
    public Object auditControllerCall(ProceedingJoinPoint joinPoint) throws Throwable {
        Instant startAt = Instant.now();
        long startNanos = System.nanoTime();

        String endpoint = resolveEndpoint(joinPoint);
        String correlationId = MDC.get("correlationId");
        String requestSummary = summarizeRequest(joinPoint.getArgs());
        String ownerId = resolveActorId("ownerId", joinPoint.getArgs());
        String userId = resolveActorId("userId", joinPoint.getArgs());

        logger.info(
            "audit_start endpoint={}, ownerId={}, userId={}, correlationId={}, startedAt={}, requestSummary={}",
                endpoint,
            ownerId,
                userId,
                correlationId,
                startAt,
                requestSummary
        );

        try {
            Object result = joinPoint.proceed();
            Instant endAt = Instant.now();
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

            logger.info(
                    "audit_end endpoint={}, ownerId={}, userId={}, correlationId={}, startedAt={}, endedAt={}, durationMs={}, status=SUCCESS",
                    endpoint,
                    ownerId,
                    userId,
                    correlationId,
                    startAt,
                    endAt,
                    durationMs
            );
            return result;
        } catch (Throwable throwable) {
            Instant endAt = Instant.now();
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

            logger.warn(
                    "audit_end endpoint={}, ownerId={}, userId={}, correlationId={}, startedAt={}, endedAt={}, durationMs={}, status=FAILED, errorType={}, errorMessage={}",
                    endpoint,
                    ownerId,
                    userId,
                    correlationId,
                    startAt,
                    endAt,
                    durationMs,
                    throwable.getClass().getSimpleName(),
                    throwable.getMessage()
            );
            throw throwable;
        }
    }

    private String resolveEndpoint(ProceedingJoinPoint joinPoint) {
        HttpServletRequest request = currentHttpRequest();
        if (request != null) {
            return request.getMethod() + " " + request.getRequestURI();
        }

        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        return methodSignature.getDeclaringType().getSimpleName() + "." + methodSignature.getName();
    }

    private String summarizeRequest(Object[] args) {
        Map<String, Object> summary = new LinkedHashMap<>();

        for (int index = 0; index < args.length; index++) {
            Object arg = args[index];
            if (shouldSkipArgument(arg)) {
                continue;
            }
            summary.put("arg" + index, arg);
        }

        String serialized = toJsonSafely(summary);
        if (serialized.length() > MAX_REQUEST_SUMMARY_LENGTH) {
            return serialized.substring(0, MAX_REQUEST_SUMMARY_LENGTH) + "...";
        }
        return serialized;
    }

    private boolean shouldSkipArgument(Object arg) {
        return arg == null
                || arg instanceof ServletRequest
                || arg instanceof ServletResponse
                || arg instanceof BindingResult
                || arg instanceof MultipartFile;
    }

    private String resolveActorId(String actorFieldName, Object[] args) {
        String fromMdc = MDC.get(actorFieldName);
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }

        HttpServletRequest request = currentHttpRequest();
        if (request != null) {
            String headerName = "ownerId".equals(actorFieldName) ? "X-Owner-Id" : "X-User-Id";
            String fromHeader = request.getHeader(headerName);
            if (fromHeader != null && !fromHeader.isBlank()) {
                return fromHeader.trim();
            }
        }

        for (Object arg : args) {
            if (arg == null) {
                continue;
            }

            if (arg instanceof Map<?, ?> mapValue) {
                Object value = mapValue.get(actorFieldName);
                if (value != null) {
                    return String.valueOf(value);
                }
            }

            String reflectedValue = readFieldAsString(arg, actorFieldName);
            if (reflectedValue != null && !reflectedValue.isBlank()) {
                return reflectedValue;
            }
        }

        return "UNKNOWN";
    }

    private HttpServletRequest currentHttpRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest();
        }
        return null;
    }

    private String readFieldAsString(Object source, String fieldName) {
        Class<?> currentType = source.getClass();
        while (currentType != null && currentType != Object.class) {
            try {
                Field field = currentType.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(source);
                if (value == null) {
                    return null;
                }
                if (value instanceof UUID uuid) {
                    return uuid.toString();
                }
                return String.valueOf(value);
            } catch (NoSuchFieldException ex) {
                currentType = currentType.getSuperclass();
            } catch (IllegalAccessException ex) {
                return null;
            }
        }
        return null;
    }

    private String toJsonSafely(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }
}
