package com.gatekeeper.qaplatform.audit;

import com.gatekeeper.qaplatform.auth.AuthenticatedUser;
import java.util.HashMap;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Runs outside the transaction aspect, so success is only recorded after the commit. */
@Aspect
@Component
@Order(0)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditService auditService;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    public AuditAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        try {
            Object result = joinPoint.proceed();
            record(joinPoint, audited, "SUCCESS", null);
            return result;
        } catch (Throwable failure) {
            record(joinPoint, audited, "FAILURE", failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** The action has already happened, so an audit problem must not change its result. */
    private void record(ProceedingJoinPoint joinPoint, Audited audited, String outcome, String error) {
        try {
            var context = new MethodBasedEvaluationContext(
                    null,
                    ((MethodSignature) joinPoint.getSignature()).getMethod(),
                    joinPoint.getArgs(),
                    parameterNames);
            String target = audited.target().isEmpty()
                    ? null
                    : String.valueOf(parser.parseExpression(audited.target()).getValue(context));

            Map<String, Object> details = new HashMap<>();
            if (!audited.details().isEmpty()) {
                Map<?, ?> extra = parser.parseExpression(audited.details()).getValue(context, Map.class);
                extra.forEach((key, value) -> details.put(String.valueOf(key), value));
            }
            details.put("outcome", outcome);
            if (error != null) {
                details.put("error", error);
            }
            auditService.record(currentActor(), audited.action(), target, details);
        } catch (RuntimeException e) {
            log.error("Could not record audit event {}", audited.action(), e);
        }
    }

    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.email();
        }
        return "system";
    }
}
