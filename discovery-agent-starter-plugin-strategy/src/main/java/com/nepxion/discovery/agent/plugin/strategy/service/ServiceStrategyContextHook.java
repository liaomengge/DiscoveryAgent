package com.nepxion.discovery.agent.plugin.strategy.service;

/**
 * <p>Title: Nepxion Discovery</p>
 * <p>Description: Nepxion Discovery</p>
 * <p>Copyright: Copyright (c) 2017-2050</p>
 * <p>Company: Nepxion</p>
 * @author zifeihan
 * @version 1.0
 */

import com.nepxion.discovery.agent.plugin.thread.ThreadConstant;
import com.nepxion.discovery.agent.threadlocal.AbstractThreadLocalHook;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

public class ServiceStrategyContextHook extends AbstractThreadLocalHook {
    private Boolean requestDecoratorEnabled = Boolean.valueOf(System.getProperty(ThreadConstant.THREAD_REQUEST_DECORATOR_ENABLED, "true"));

    @Override
    public Object create() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        return new Object[]{request};
    }

    @Override
    public void before(Object object) {
        if (object.getClass().isArray()) {
            Object[] objects = (Object[]) object;

            if (objects[0] instanceof RequestAttributes) {
                RequestAttributes requestAttributes = (RequestAttributes) objects[0];
                RequestContextHolder.setRequestAttributes(requestAttributes);
            }
        }
    }

    @Override
    public void after() {
        RequestContextHolder.resetRequestAttributes();
    }
}