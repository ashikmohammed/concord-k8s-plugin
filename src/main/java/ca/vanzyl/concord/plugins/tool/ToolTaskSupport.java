package ca.vanzyl.concord.plugins.tool;


import ca.vanzyl.concord.plugins.tool.annotations.Flag;
import ca.vanzyl.concord.plugins.tool.annotations.KeyValue;
import ca.vanzyl.concord.plugins.tool.annotations.Omit;
import ca.vanzyl.concord.plugins.tool.annotations.Option;
import ca.vanzyl.concord.plugins.tool.annotations.OptionWithEquals;
import ca.vanzyl.concord.plugins.tool.annotations.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import ca.vanzyl.concord.plugins.Configurator;
import com.walmartlabs.concord.sdk.Context;
import com.walmartlabs.concord.sdk.LockService;
import com.walmartlabs.concord.sdk.Task;
import io.airlift.units.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public abstract class ToolTaskSupport implements Task {

    private static final Logger logger = LoggerFactory.getLogger(ToolTaskSupport.class);

    protected final LockService lockService;
    protected final ToolInitializer toolInitializer;
    protected final Configurator toolConfigurator;
    protected final Map<String, ToolCommand> commands;

    public ToolTaskSupport(Map<String, ToolCommand> commands, LockService lockService, ToolInitializer toolInitializer) {
        this.commands = commands;
        this.lockService = lockService;
        this.toolInitializer = toolInitializer;
        this.toolConfigurator = new Configurator();
    }

    public void execute(Context context) throws Exception {

        // Task name taken from the @Named annotation. Inside of Guice a generate wrapper is created, but for testing
        // where we are not wiring with Guice we need to look at the class directly.
        Named named = this.getClass().getSuperclass().getAnnotation(Named.class);
        if (named == null) {
            named = this.getClass().getAnnotation(Named.class);
        }
        String taskName = named.value();

        Path workDir = Paths.get((String) context.getVariable(com.walmartlabs.concord.sdk.Constants.Context.WORK_DIR_KEY));
        if (workDir == null) {
            throw new IllegalArgumentException("Can't determine the current '" + com.walmartlabs.concord.sdk.Constants.Context.WORK_DIR_KEY + "'");
        }

        // Retrieve the name of the command from the configuration
        String toolCommandName = (String) context.getVariable("command");

        // Retrieve the configuration as a createConfiguration from the context
        Map<String, Object> configurationAsMap = variables(context);

        // Retrieve the common configuration elements for all commands:
        //
        // version
        // url
        // debug
        // dryRun
        //
        ToolConfiguration toolConfiguration = toolConfigurator.createConfiguration(configurationAsMap, ToolConfiguration.class);

        // Retrieve the specific command as specified by the "command" key in the configuration
        ToolCommand toolCommand = commands.get(taskName + "/" + toolCommandName);

        if (toolCommand == null) {
            throw new RuntimeException(String.format("Cannot find the command '%s'/%s'", taskName, toolCommandName));
        }

        // Apply the configuration to the command
        toolConfigurator.configure(toolCommand, variables(context));

        ToolDescriptor toolDescriptor = toolDescriptor(taskName, toolConfiguration);

        // Initialize the specific tool and make it available to concord for use
        ToolInitializationResult toolInitializationResult = toolInitializer.initialize(workDir, toolDescriptor, toolConfiguration.debug());

        logger.info("We have successfully initialized {} version {}.", toolDescriptor.name(), toolDescriptor.version() != null ? toolDescriptor.version() : toolDescriptor.defaultVersion());

        // Build up the arguments for the execution of this tool: executable +
        List<String> args = Lists.newArrayList();
        args.add(toolInitializationResult.executable().toFile().getAbsolutePath());
        args.addAll(generateCommandLineArguments(toolCommandName, toolCommand));
        CliCommand command = new CliCommand(
                args,
                ImmutableSet.of(0),
                workDir,
                toolConfiguration.envars(),
                Duration.succinctDuration(20, TimeUnit.MINUTES)
        );

        // Here is where we want to alter what Helm install is doing. If there is an externals configuration we want
        // fetch the Helm chart, insert the externals into the Helm chart and then install from the directory we
        // created with the fetched Helm chart
        //
        // - do any preparation work here and run any commands necessary. i need the path to the executable and access
        //   to the command and its configuration
        // - change the command line arguments as necessary. in the case of Helm we need to install from the directory
        //   just created.

        // Place envar into the context
        context.setVariable("envars", toolConfiguration.envars());

        String commandLineArguments = String.join(" ", command.getCommand());
        if (toolConfiguration.dryRun()) {
            context.setVariable("commandLineArguments", String.join(" ", command.getCommand()));
            context.setVariable("dryRun", "true");
            //
            // Command pre-processing
            //
            toolCommand.preProcess(workDir, context);
            logger.info(commandLineArguments);
            //
            // Command post-processing
            //
            toolCommand.postProcess(workDir, context);
        } else {

            if (toolCommand.idempotencyCheckCommand(context) != null) {

                String idempotencyCheckCommand = toolCommand.idempotencyCheckCommand(context);
                idempotencyCheckCommand = mustache(idempotencyCheckCommand, "executable", toolInitializationResult.executable().toFile().getAbsolutePath());
                logger.info("idempotencyCheckCommand: " + idempotencyCheckCommand);

                //
                // "{{executable}} get cluster --name {{name}} --region {{region}} -o json"
                //
                CliCommand idempotencyCheck = new CliCommand(
                        Lists.newArrayList(idempotencyCheckCommand.split(" ")),
                        ImmutableSet.of(0),
                        workDir,
                        toolConfiguration.envars(),
                        Duration.succinctDuration(20, TimeUnit.MINUTES));

                CliCommand.Result result = idempotencyCheck.execute(Executors.newCachedThreadPool());
                
                if (result.getCode() == toolCommand.expectedIdempotencyCheckReturnValue()) {

                    logger.info("This command has already run successfully: " + command.getCommand());
                    //
                    // The task we are intending to run has already executed successfully. It is the job of the idempotency
                    // command to ask if what we intend to do has already been done.
                    //
                    return;
                }
            }

            //
            // Command pre-processing
            //
            toolCommand.preProcess(workDir, context);
            logger.info("Executing: " + commandLineArguments);
            CliCommand.Result commandResult = command.execute(Executors.newCachedThreadPool());
            if (commandResult.getCode() != 0) {
                throw new RuntimeException(String.format("The command %s failed. Look in the logs above.", commandLineArguments));
            }
            //
            // Command post-processing
            //
            toolCommand.postProcess(workDir, context);
        }
    }

    // TODO: clean up repeated code and come up with a model for generating this
    // TODO: separate this code into its own class
    // TODO: ignore private static final fields
    public static List<String> generateCommandLineArguments(String commandName, Object command) throws Exception {
        //
        // eksctl create cluster --config-file cluster.yaml --kubeconfig /home/concord/.kube/config
        //
        // kubectl apply -f 00-helm/tiller-rbac.yml
        //
        List<String> arguments = Lists.newArrayList();

        Value v = command.getClass().getSuperclass().getAnnotation(Value.class);
        if (v == null) {
            v = command.getClass().getAnnotation(Value.class);
        }
        if (v != null) {
            arguments.add(v.value());
        } else {
            arguments.add(commandName);
        }

        for (Field field : command.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            if (Modifier.isFinal(field.getModifiers())) {
                continue;
            }
            Object operand = field.get(command);
            if (operand != null) {
                if (operand.getClass().isPrimitive() || Boolean.class.isAssignableFrom(operand.getClass()) || String.class.isAssignableFrom(operand.getClass())) {
                    Option option = field.getAnnotation(Option.class);
                    if (option != null) {
                        Object value = field.get(command);
                        if (value != null) {
                            arguments.add(option.name()[0]);
                            arguments.add((String) value);
                        }
                    } else if (field.getAnnotation(Value.class) != null) {
                        System.out.println("field.getName() = " + field.getName());
                        Object value = field.get(command);
                        System.out.println("value = " + value);
                        arguments.add((String) value);
                    } else {
                        Flag flag = field.getAnnotation(Flag.class);
                        if (flag != null) {
                            arguments.add(flag.name()[0]);
                        }
                        OptionWithEquals optionWithEquals = field.getAnnotation(OptionWithEquals.class);
                        if (optionWithEquals != null) {
                            Object value = field.get(command);
                            if (value != null) {
                                arguments.add(optionWithEquals.name()[0] + "=" + value);
                            }
                        }
                    }
                } else {
                    // helm install <omit chart> --name xxx --values yyy
                    Omit omit = field.getAnnotation(Omit.class);
                    if (omit == null) {
                        arguments.add(field.getName());
                    }
                    for (Field configuration : operand.getClass().getDeclaredFields()) {
                        Option option = configuration.getAnnotation(Option.class);
                        if (option != null) {
                            configuration.setAccessible(true);
                            Object value = configuration.get(operand);
                            if (value != null) {
                                if (!option.omitFor().equals(command.getClass())) {
                                    arguments.add(option.name()[0]);
                                }
                                arguments.add((String) value);
                            }
                        } else if (configuration.getAnnotation(KeyValue.class) != null) {
                            KeyValue annotion = configuration.getAnnotation(KeyValue.class);
                            configuration.setAccessible(true);
                            Object fieldValue = configuration.get(operand);
                            if (fieldValue != null) {
                                // --set
                                String parameter = annotion.name();
                                List<String> kvs = (List<String>) fieldValue;
                                for (String e : kvs) {
                                    // --set "ingress.hostname=bob.fetesting.com"
                                    arguments.add(parameter);
                                    arguments.add(e);
                                }
                            }
                        } else {
                            Flag flag = configuration.getAnnotation(Flag.class);
                            if (flag != null) {
                                configuration.setAccessible(true);
                                boolean value = (boolean) configuration.get(operand);
                                if (value) {
                                    arguments.add(flag.name()[0]);
                                }
                            } else {
                                System.out.println("configuration = " + configuration);
                                configuration.setAccessible(true);
                                Object value = configuration.get(operand);
                                arguments.add((String) value);
                            }
                        }
                    }
                }
            }
        }
        return arguments;
    }

    public static ToolDescriptor fromResource(String taskName) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream inputStream = ToolTaskSupport.class.getClassLoader().getResourceAsStream(taskName + "/" + "descriptor.yml")) {
            return mapper.readValue(inputStream, ToolDescriptor.class);
        }
    }

    private String mustache(String s, String var, String replacement) {
        return s.replaceAll("\\{\\{" + var + "\\}\\}", replacement);
    }

    protected Map<String, Object> variables(Context context) {
        Map<String, Object> variables = Maps.newHashMap();
        for (String key : context.getVariableNames()) {
            variables.put(key, context.getVariable(key));
        }
        return variables;
    }

    protected ToolDescriptor toolDescriptor(String taskName, ToolConfiguration toolConfiguration) throws Exception {

        ToolDescriptor toolDescriptor = fromResource(taskName);

        // Update the version if overriden by the user
        if (toolConfiguration.version() != null) {
            toolDescriptor = ImmutableToolDescriptor.copyOf(toolDescriptor).withVersion(toolConfiguration.version());
        }

        // Update the url if overriden by the user
        if (toolConfiguration.url() != null) {
            toolDescriptor = ImmutableToolDescriptor.copyOf(toolDescriptor).withUrlTemplate(toolConfiguration.url());
        }

        return toolDescriptor;
    }

    protected String processId(Context context) {
        return (String) context.getVariable(com.walmartlabs.concord.sdk.Constants.Context.TX_ID_KEY);
    }

    protected Path workDir(Context context) {
        Path workDir = Paths.get((String) context.getVariable(com.walmartlabs.concord.sdk.Constants.Context.WORK_DIR_KEY));
        if (workDir == null) {
            throw new IllegalArgumentException("Can't determine the current '" + com.walmartlabs.concord.sdk.Constants.Context.WORK_DIR_KEY + "'");
        }
        return workDir;
    }

}