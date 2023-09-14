package org.sagebionetworks.template.datawarehouse.backfill;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.sagebionetworks.template.TemplateGuiceModule;

public class BackfillWarehouseBuilderMain {
    public static void main(String[] args) throws InterruptedException {
        Injector injector = Guice.createInjector(new TemplateGuiceModule());

         BackfillWarehouseBuilderImpl builder = injector.getInstance(BackfillWarehouseBuilderImpl.class);

        builder.buildAndDeploy();
    }
}
