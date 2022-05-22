package com.charlyghislain.resourcewatcher;

import com.charlyghislain.resourcewatcher.config.ResourceWatcherConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ResourceWatcherConfigFactory {
    public static ResourceWatcherConfig fromYamlFile(Path configPath) throws Exception {
        Yaml yaml = new Yaml();
        try {
            ResourceWatcherConfig config = yaml.loadAs(Files.newBufferedReader(configPath), ResourceWatcherConfig.class);
            return config;
        } catch (IOException e) {
            throw new Exception("Unable to load config at " + configPath + ": " + e.getMessage(), e);
        }
    }
}
