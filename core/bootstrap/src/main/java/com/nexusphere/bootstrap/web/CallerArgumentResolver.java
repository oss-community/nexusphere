package com.nexusphere.bootstrap.web;

import com.nexusphere.membership.contract.PrincipalResolver;
import com.nexusphere.shared.context.Caller;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.HashSet;

class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    private final PrincipalResolver principals;

    CallerArgumentResolver(PrincipalResolver principals) {
        this.principals = principals;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Caller.class.equals(parameter.getParameterType());
    }

    @Override
    public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        if (AuthenticatedIdentity.operator()) {
            return Caller.platformOperator();
        }
        return AuthenticatedIdentity.current()
                .map(identity -> new Caller(identity, false, new HashSet<>(principals.memberNetworks(identity)),
                        new HashSet<>(principals.administeredNetworks(identity))))
                .orElseGet(Caller::anonymous);
    }
}
