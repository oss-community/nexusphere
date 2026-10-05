package com.nexusphere.bootstrap.web;

import com.nexusphere.membership.contract.PrincipalResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    private final PrincipalResolver principals;

    WebConfiguration(PrincipalResolver principals) {
        this.principals = principals;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ExecutionContextArgumentResolver());
        resolvers.add(new PrincipalContextArgumentResolver(principals));
    }
}
