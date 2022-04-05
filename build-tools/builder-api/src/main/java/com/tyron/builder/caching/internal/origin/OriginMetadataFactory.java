package com.tyron.builder.caching.internal.origin;

import com.tyron.builder.caching.internal.CacheableEntity;

import java.time.Duration;
import java.util.Properties;
import java.util.logging.Logger;

public class OriginMetadataFactory {

    private static final Logger LOGGER = Logger.getLogger(OriginMetadataFactory.class.getSimpleName());

    private static final String BUILD_INVOCATION_ID_KEY = "buildInvocationId";
    private static final String TYPE_KEY = "type";
    private static final String IDENTITY_KEY = "identity";
    private static final String CREATION_TIME_KEY = "creationTime";
    private static final String EXECUTION_TIME_KEY = "executionTime";
    private static final String OPERATING_SYSTEM_KEY = "operatingSystem";
    private static final String HOST_NAME_KEY = "hostName";
    private static final String USER_NAME_KEY = "userName";

    private final String userName;
    private final String operatingSystem;
    private final String currentBuildInvocationId;
    private final PropertiesConfigurator additionalProperties;
    private final HostnameLookup hostnameLookup;

    public OriginMetadataFactory(
            String userName,
            String operatingSystem,
            String currentBuildInvocationId,
            PropertiesConfigurator additionalProperties,
            HostnameLookup hostnameLookup
    ) {
        this.userName = userName;
        this.operatingSystem = operatingSystem;
        this.additionalProperties = additionalProperties;
        this.currentBuildInvocationId = currentBuildInvocationId;
        this.hostnameLookup = hostnameLookup;
    }

    public OriginWriter createWriter(CacheableEntity entry, Duration elapsedTime) {
        return outputStream -> {
            Properties properties = new Properties();
            properties.setProperty(BUILD_INVOCATION_ID_KEY, currentBuildInvocationId);
            properties.setProperty(TYPE_KEY, entry.getType().getCanonicalName());
            properties.setProperty(IDENTITY_KEY, entry.getIdentity());
            properties.setProperty(CREATION_TIME_KEY, Long.toString(System.currentTimeMillis()));
            properties.setProperty(EXECUTION_TIME_KEY, Long.toString(elapsedTime.toMillis()));
            properties.setProperty(OPERATING_SYSTEM_KEY, operatingSystem);
            properties.setProperty(HOST_NAME_KEY, hostnameLookup.getHostname());
            properties.setProperty(USER_NAME_KEY, userName);
            additionalProperties.configure(properties);
            properties.store(outputStream, "Generated origin information");
        };
    }

    public OriginReader createReader(CacheableEntity entry) {
        return inputStream -> {
            Properties properties = new Properties();
            properties.load(inputStream);
//            if (LOGGER.isDebugEnabled()) {
                LOGGER.info("Origin for " + entry.getDisplayName() + ": " + properties);
//            }

            String originBuildInvocationId = properties.getProperty(BUILD_INVOCATION_ID_KEY);
            String executionTimeAsString = properties.getProperty(EXECUTION_TIME_KEY);

            if (originBuildInvocationId == null || executionTimeAsString == null) {
                throw new IllegalStateException("Cached result format error, corrupted origin metadata");
            }

            Duration originalExecutionTime = Duration.ofMillis(Long.parseLong(executionTimeAsString));
            return new OriginMetadata(originBuildInvocationId, originalExecutionTime);
        };
    }

    public interface PropertiesConfigurator {
        void configure(Properties properties);
    }

    public interface HostnameLookup {
        String getHostname();
    }
}