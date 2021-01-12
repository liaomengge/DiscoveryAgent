package com.nepxion.discovery.agent.plugin.strategy.zuul;

/**
 * <p>Title: Nepxion Discovery</p>
 * <p>Description: Nepxion Discovery</p>
 * <p>Copyright: Copyright (c) 2017-2050</p>
 * <p>Company: Nepxion</p>
 * @author zifeihan
 * @version 1.0
 */

import com.nepxion.discovery.agent.threadlocal.AbstractThreadLocalHook;
import com.netflix.zuul.context.RequestContext;

import javax.servlet.http.HttpServletRequest;

public class ZuulStrategyContextHook extends AbstractThreadLocalHook {
    @Override
    public Object create() {
        HttpServletRequest request = RequestContext.getCurrentContext().getRequest();
        return new Object[]{request};
    }

    @Override
    public void before(Object object) {
        if (object.getClass().isArray()) {
            Object[] objects = (Object[]) object;

            if (objects[0] instanceof HttpServletRequest) {
                RequestContext.getCurrentContext().setRequest((HttpServletRequest) objects[0]);
            }
        }
    }

    @Override
    public void after() {
        RequestContext.getCurrentContext().unset();
    }
}