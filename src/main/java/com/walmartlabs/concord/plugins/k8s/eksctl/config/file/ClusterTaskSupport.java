package com.walmartlabs.concord.plugins.k8s.eksctl.config.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireeye.k8s.ClusterGenerationRequest;
import com.google.common.collect.Maps;
import com.walmartlabs.concord.sdk.Constants;
import com.walmartlabs.concord.sdk.Context;
import com.walmartlabs.concord.sdk.ProjectInfo;
import com.walmartlabs.concord.sdk.SecretService;
import com.walmartlabs.concord.sdk.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Map;

public abstract class ClusterTaskSupport implements Task {

    private static final Logger logger = LoggerFactory.getLogger(ClusterTaskSupport.class);

    protected final ObjectMapper yamlMapper;
    protected final SecretService secretService;

    public ClusterTaskSupport(SecretService secretService) {
        this.secretService = secretService;
        this.yamlMapper = new YamlObjectMapperProvider().get();
    }

    protected ClusterGenerationRequest read(File requestFile) throws Exception {
        try (Reader reader = new InputStreamReader(new FileInputStream(requestFile))) {
            ClusterGenerationRequest request = yamlMapper.readValue(reader, ClusterGenerationRequest.class);
            return request;
        }
    }
}
