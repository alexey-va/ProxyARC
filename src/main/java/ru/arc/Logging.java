package ru.arc;

import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.BurstFilter;
import org.apache.logging.log4j.core.layout.JsonLayout;
import org.apache.logging.log4j.core.layout.PatternLayout;
import pl.tkowalcz.tjahzi.log4j2.Header;
import pl.tkowalcz.tjahzi.log4j2.LokiAppender;
import pl.tkowalcz.tjahzi.log4j2.labels.Label;
import ru.arc.config.Config;
import ru.arc.config.ConfigManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

@Slf4j
public class Logging {

    public static void addLokiAppender(Path folder) {
        try {
            Config config = ConfigManager.of(folder, "logging.yml");
            Label[] labels = config.map("labels", Map.of()).entrySet().stream()
                    .filter(l -> {
                        if (!Label.hasValidName(l.getKey())) {
                            log.warn("Invalid label: {}", l);
                            return false;
                        }
                        if (l.getValue() == null || !(l.getValue() instanceof String)) {
                            log.warn("Null value for label: {}", l);
                            return false;
                        }
                        return true;
                    }).map(l -> Label.createLabel(l.getKey(), (String) l.getValue(), null))
                    .toArray(Label[]::new);
            Header[] headers = new Header[labels.length];
            for (int i = 0; i < labels.length; i++) {
                Label label = labels[i];
                headers[i] = Header.createHeader(label.getName(), label.getValue());
            }

            PatternLayout layout = PatternLayout.newBuilder()
                    .withPattern("{\"instant\":{\"epochSecond\":%d{UNIX},\"nanoOfSecond\":%nano},"
                            + "\"thread\":\"%t\","
                            + "\"level\":\"%p\","
                            + "\"loggerName\":\"%c\","
                            + "\"message\":\"%enc{%m}{JSON}\","
                            + "\"endOfBatch\":false,"  // not provided by LogEvent so hard-coded
                            + "\"loggerFqcn\":\"%fqcn\","
                            + "\"threadId\":%tid,"
                            + "\"threadPriority\":%threadPriority}%n")
                    .withCharset(StandardCharsets.UTF_8)
                    .build();


            var builder = LokiAppender.newBuilder();
            builder.setHost(config.string("host"));
            builder.setPort(config.integer("port", 3100));
            builder.setLabels(labels);
            builder.setHeaders(headers);
            builder.setName("lokiAppender");
            builder.setLayout(layout);
            LokiAppender build = builder.build();
            build.start();

            Logger rootLogger = (Logger) LogManager.getRootLogger();
            Configuration configuration = rootLogger.getContext().getConfiguration();
            configuration.addAppender(build);
            LoggerConfig loggerConfig = configuration.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
            loggerConfig.addAppender(build, Level.INFO, null);
            rootLogger.getContext().updateLoggers();
        } catch (Exception e) {
            log.warn("Failed to add lokiAppender", e);
        }
    }
}
