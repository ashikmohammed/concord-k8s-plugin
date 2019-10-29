package com.walmartlabs.concord.plugins.k8s.helm.commands;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.walmartlabs.concord.plugins.k8s.helm.config.Chart;
import com.walmartlabs.concord.plugins.tool.Flag;
import com.walmartlabs.concord.plugins.tool.Omit;
import com.walmartlabs.concord.plugins.tool.ToolCommandSupport;
import com.walmartlabs.concord.sdk.Context;
import io.airlift.airline.Option;

import javax.inject.Named;
import java.nio.file.Path;

@Named("helm/install")
public class Install extends ToolCommandSupport {

    @JsonProperty("chart")
    @Omit
    private Chart chart;

    public Chart chart() {
        return chart;
    }

    @Override
    public String idempotencyCheckCommand() {
        return String.format("{{executable}} status %s", chart.name());
    }

    public void modifyCommandFlow(Context context) {
        //
        // Here is where we want to alter what Helm install is doing. If there is an externals configuration we want
        // fetch the Helm chart, insert the externals into the Helm chart and then install from the directory we
        // created with the fetched Helm chart
        //
        // - do any preparation work here and run any commands necessary. i need the path to the executable and access
        //   to the command and its configuration
        // - change the command line arguments as necessary. in the case of Helm we need to install from the directory
        //   just created.
        //
        if(chart.externals() != null) {
            //
            // helm fetch --version 1.7.4 --untar --untardir jenkins stable/jenkins
            //
            Path untardir = workDir(context).resolve("chart-" + chart.name());
            //
            // 1 = version
            // 2 = untardir
            // 3 = chart
            //
            String.format("{{executable}} fetch --version %s --untar --untardir %s %s", chart.version(), chart.name(), chart.value());
        }
    }
}