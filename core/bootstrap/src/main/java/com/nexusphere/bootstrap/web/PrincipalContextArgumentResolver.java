package com.nexusphere.bootstrap.web;

import com.nexusphere.membership.contract.NetworkAccessDenied;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.membership.contract.PrincipalResolver;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.time.TimeProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

class PrincipalContextArgumentResolver implements HandlerMethodArgumentResolver {

    static final String NETWORK_HEADER = "X-Network-Id";

    private final PrincipalResolver principals;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    PrincipalContextArgumentResolver(PrincipalResolver principals, DomainEventPublisher events, TimeProvider time) {
        this.principals = principals;
        this.events = events;
        this.time = time;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PrincipalContext.class.equals(parameter.getParameterType());
    }

    @Override
    public PrincipalContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        IdentityId identity = AuthenticatedIdentity.current().orElseThrow(() -> new DomainException(
                ErrorCategory.AUTHENTICATION_ERROR, "AUTHENTICATION_REQUIRED", "A bearer token is required"));
        NetworkId network = network(request);
        try {
            return principals.resolve(identity, network);
        } catch (DomainException e) {
            if ("NETWORK_ACCESS_DENIED".equals(e.code())) {
                ExecutionContext context = new ExecutionContext(RequestCorrelation.of(request), identity, null, null,
                        null);
                principals.memberNetworks(identity).forEach(home -> events.publish(
                        new NetworkAccessDenied(time.now(), identity, home, network, e.code()), context));
            }
            throw e;
        }
    }

    private static NetworkId network(HttpServletRequest request) {
        String header = request.getHeader(NETWORK_HEADER);
        String path = pathNetwork(request);
        if (header != null && path != null && !header.equalsIgnoreCase(path)) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "NETWORK_CONTEXT_MISMATCH",
                    "The " + NETWORK_HEADER + " header does not match the network of the request path");
        }
        String value = path != null ? path : header;
        if (value == null) {
            throw new ValidationException("NETWORK_CONTEXT_REQUIRED", "The " + NETWORK_HEADER + " header is required");
        }
        return NetworkId.of(value);
    }

    @SuppressWarnings("unchecked")
    private static String pathNetwork(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? ((Map<String, String>) map).get("networkId") : null;
    }
}
