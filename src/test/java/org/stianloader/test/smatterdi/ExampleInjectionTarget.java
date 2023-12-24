package org.stianloader.test.smatterdi;

import org.stianloader.smatterdi.Autowire;
import org.stianloader.smatterdi.Inject;

@Autowire
public abstract class ExampleInjectionTarget {
    @Inject
    public abstract InjectedObject getInjectedObject();
}
