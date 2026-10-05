package com.nexusphere.bootstrap.web;

import com.nexusphere.shared.context.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class ExecutionContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return ExecutionContext.class.equals(parameter.getParameterType());
    }

    @Override
    public ExecutionContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        ExecutionContext context = ExecutionContext.anonymous(RequestCorrelation.of(request));
        return AuthenticatedIdentity.current()
                .map(identity -> new ExecutionContext(context.correlationId(), identity, null, null, null))
                .orElse(context);
    }
}
