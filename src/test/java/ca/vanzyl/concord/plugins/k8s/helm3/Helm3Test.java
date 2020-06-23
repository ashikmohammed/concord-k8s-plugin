package ca.vanzyl.concord.plugins.k8s.helm3;

import ca.vanzyl.concord.plugins.Configurator;
import ca.vanzyl.concord.plugins.k8s.helm3.commands.Install;
import ca.vanzyl.concord.plugins.k8s.helm3.commands.Repo;
import ca.vanzyl.concord.plugins.k8s.helm3.commands.Upgrade;
import ca.vanzyl.concord.plugins.tool.*;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.walmartlabs.concord.plugins.ConcordTestSupport;
import com.walmartlabs.concord.plugins.InterpolatingMockContext;
import com.walmartlabs.concord.plugins.OKHttpDownloadManager;
import com.walmartlabs.concord.sdk.Context;
import com.walmartlabs.concord.sdk.MockContext;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.walmartlabs.concord.sdk.Constants.Context.WORK_DIR_KEY;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.Assert.assertTrue;

public class Helm3Test extends ConcordTestSupport
{

    private static final String TOOL_NAME = "helm3";

    private ToolDescriptor toolDescriptor;
    private Configurator toolConfigurator;

    @Before
    public void setUp() throws Exception {
        toolConfigurator = new Configurator();
        toolDescriptor = ToolTaskSupport.fromResource(TOOL_NAME);
        super.setUp();
    }

    @Test
    public void validateToolInitializerWithHelm() throws Exception {

        Path workingDirectory = Files.createTempDirectory("concord");
        deleteDirectory(workingDirectory);

        ToolInitializer toolInitializer = new ToolInitializer(new OKHttpDownloadManager(TOOL_NAME));
        ToolInitializationResult result = toolInitializer.initialize(workingDirectory, toolDescriptor);

        assertTrue(result.executable().toFile().exists());
        assertTrue(Files.isExecutable(result.executable()));

        deleteDirectory(workingDirectory);
    }


    @Test
    public void validateHelmInstall() throws Exception {

        ToolInitializer toolInitializer = new ToolInitializer(new OKHttpDownloadManager(TOOL_NAME));
        Map<String, ToolCommand> commands = ImmutableMap.of("helm3/install", new Install());
        HelmTask task = new HelmTask(commands, lockService, toolInitializer);

        // Create a values.yml file and make sure it's interpolated correctly
        Path valuesYaml = Files.createTempFile("helm", "values.yaml");
        System.out.println("Creating Helm values.yml: " + valuesYaml.toString());
        StringBuffer sb = new StringBuffer();
        sb.append("ingress:").append("\n");
        sb.append("  hostname: ${hostname}");
        Files.write(valuesYaml, sb.toString().getBytes());

        Map<String, Object> args = Maps.newHashMap(mapBuilder()
                .put(WORK_DIR_KEY, workDir.toAbsolutePath().toString())
                .put("dryRun", true)
                .put("command", "install")
                .put("chart",
                        mapBuilder()
                                .put("name", "sealed-secrets")
                                .put("namespace", "kube-system")
                                .put("version", "1.4.2")
                                .put("set", ImmutableList.of("expose.ingress.host.core=bob.fetesting.com"))
                                .put("value", "stable/sealed-secrets")
                                .put("values", valuesYaml.toString())
                                .build())
                .put("envars",
                        mapBuilder()
                                .put("AWS_ACCESS_KEY_ID", "aws-access-key")
                                .put("AWS_SECRET_ACCESS_KEY", "aws-secret-key")
                                .put("KUBECONFIG", "/workspace/_attachments/k8s-cluster0-kubeconfig")
                                .build())
                .put("hostname", "awesome.concord.io")
                .build());

        Context context = new InterpolatingMockContext(args);
        task.execute(context);
        String commandLine = varAsString(context, "commandLineArguments");

        System.out.println(commandLine);

        // Make sure our values.yaml file was interpolated correctly by the Helm install command
        String interpolatedContent = new String(Files.readAllBytes(valuesYaml));
        assertThat(interpolatedContent).contains("hostname: awesome.concord.io");

        String expectedCommandLine = String.format(
                "%s install --namespace kube-system --version 1.4.2 --set expose.ingress.host.core=bob.fetesting.com -f %s sealed-secrets stable/sealed-secrets --atomic --create-namespace",
                toolDescriptor.executable(),
                valuesYaml.toString());
        assertThat(normalizedCommandLineArguments(context)).endsWith(expectedCommandLine);

        System.out.println(context.getVariable("envars"));

        // If these were placed in the context, then they were added to the environment of the executed command
        assertThat(varAsMap(context, "envars").get("KUBECONFIG")).isEqualTo("/workspace/_attachments/k8s-cluster0-kubeconfig");
        assertThat(varAsMap(context, "envars").get("AWS_ACCESS_KEY_ID")).isEqualTo("aws-access-key");
        assertThat(varAsMap(context, "envars").get("AWS_SECRET_ACCESS_KEY")).isEqualTo("aws-secret-key");
    }

    @Test
    public void validateHelmUpgrade() throws Exception {

        ToolInitializer toolInitializer = new ToolInitializer(new OKHttpDownloadManager(TOOL_NAME));
        Map<String, ToolCommand> commands = ImmutableMap.of("helm3/upgrade", new Upgrade());
        HelmTask task = new HelmTask(commands, lockService, toolInitializer);

        // Create a values.yml file and make sure it's interpolated correctly
        Path valuesYaml = Files.createTempFile("helm", "values.yaml");
        System.out.println("Creating Helm values.yml: " + valuesYaml.toString());
        StringBuffer sb = new StringBuffer();
        sb.append("ingress:").append("\n");
        sb.append("  hostname: ${hostname}");
        Files.write(valuesYaml, sb.toString().getBytes());

        Map<String, Object> args = Maps.newHashMap(mapBuilder()
                .put(WORK_DIR_KEY, workDir.toAbsolutePath().toString())
                .put("dryRun", true)
                .put("command", "upgrade")
                .put("chart",
                        mapBuilder()
                                .put("name", "sealed-secrets")
                                .put("namespace", "kube-system")
                                .put("version", "1.4.2")
                                .put("set", ImmutableList.of("expose.ingress.host.core=bob.fetesting.com"))
                                .put("value", "stable/sealed-secrets")
                                .put("values", valuesYaml.toString())
                                .build())
                .put("envars",
                        mapBuilder()
                                .put("AWS_ACCESS_KEY_ID", "aws-access-key")
                                .put("AWS_SECRET_ACCESS_KEY", "aws-secret-key")
                                .put("KUBECONFIG", "/workspace/_attachments/k8s-cluster0-kubeconfig")
                                .build())
                .put("hostname", "awesome.concord.io")
                .build());

        Context context = new InterpolatingMockContext(args);
        task.execute(context);
        String commandLine = varAsString(context, "commandLineArguments");

        System.out.println(commandLine);

        // Make sure our values.yaml file was interpolated correctly by the Helm install command
        String interpolatedContent = new String(Files.readAllBytes(valuesYaml));
        assertThat(interpolatedContent).contains("hostname: awesome.concord.io");

        String expectedCommandLine = String.format(
                "%s upgrade --install --atomic --create-namespace --namespace kube-system --version 1.4.2 --set expose.ingress.host.core=bob.fetesting.com -f %s sealed-secrets stable/sealed-secrets",
                toolDescriptor.executable(),
                valuesYaml.toString());
        assertThat(normalizedCommandLineArguments(context)).endsWith(expectedCommandLine);

        System.out.println(context.getVariable("envars"));

        // If these were placed in the context, then they were added to the environment of the executed command
        assertThat(varAsMap(context, "envars").get("KUBECONFIG")).isEqualTo("/workspace/_attachments/k8s-cluster0-kubeconfig");
        assertThat(varAsMap(context, "envars").get("AWS_ACCESS_KEY_ID")).isEqualTo("aws-access-key");
        assertThat(varAsMap(context, "envars").get("AWS_SECRET_ACCESS_KEY")).isEqualTo("aws-secret-key");
    }

    @Test
    public void validateHelmAddRepo() throws Exception {

        ToolInitializer toolInitializer = new ToolInitializer(new OKHttpDownloadManager(TOOL_NAME));
        Map<String, ToolCommand> commands = ImmutableMap.of("helm3/repo", new Repo());
        HelmTask task = new HelmTask(commands, lockService, toolInitializer);

        Map<String, Object> args = Maps.newHashMap(mapBuilder()
                .put(WORK_DIR_KEY, workDir.toAbsolutePath().toString())
                .put("dryRun", true)
                .put("command", "repo")
                .put("add",
                        mapBuilder()
                                .put("name", "jetstack")
                                .put("url", "https://charts.jetstack.io")
                                .build())
                .build());

        Context context = new MockContext(args);
        task.execute(context);

        String expectedCommandLine = toolDescriptor.executable() + " repo add jetstack https://charts.jetstack.io";
        assertThat(normalizedCommandLineArguments(context)).endsWith(expectedCommandLine);
    }

    //
    // helm3
    // helm repo add [NAME] [URL] [flags]
    //
    // vs
    //
    // helm2
    // helm repo add [flags] [NAME] [URL]
    //
    @Test
    public void validateHelmAddRepoAuth() throws Exception {

        ToolInitializer toolInitializer = new ToolInitializer(new OKHttpDownloadManager(TOOL_NAME));
        Map<String, ToolCommand> commands = ImmutableMap.of("helm3/repo", new Repo());
        HelmTask task = new HelmTask(commands, lockService, toolInitializer);

        Map<String, Object> args = Maps.newHashMap(mapBuilder()
                .put(WORK_DIR_KEY, workDir.toAbsolutePath().toString())
                .put("dryRun", true)
                .put("command", "repo")
                .put("add",
                        mapBuilder()
                                .put("name", "jetstack")
                                .put("url", "https://charts.jetstack.io")
                                .put("username", "admin")
                                .put("password", "secret")
                                .build())
                .build());

        Context context = new MockContext(args);
        task.execute(context);

        String expectedCommandLine = toolDescriptor.executable() + " repo add jetstack https://charts.jetstack.io --username=admin --password=secret";
        assertThat(normalizedCommandLineArguments(context)).endsWith(expectedCommandLine);
    }
}
