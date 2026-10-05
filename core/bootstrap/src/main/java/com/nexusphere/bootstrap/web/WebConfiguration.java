package com.nexusphere.bootstrap.web;

import com.nexusphere.membership.contract.PrincipalResolver;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    private final PrincipalResolver principals;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    WebConfiguration(PrincipalResolver principals, DomainEventPublisher events, TimeProvider time) {
        this.principals = principals;
        this.events = events;
        this.time = time;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ExecutionContextArgumentResolver());
        resolvers.add(new PrincipalContextArgumentResolver(principals, events, time));
        resolvers.add(new CallerArgumentResolver(principals));
    }
}
