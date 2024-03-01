package ru.arc;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class ConfigsTest {

    @Test
    void testConfigs(){
        ConfigManager.create(Path.of(".."), "just_test.yml", "jt");
        Config config = ConfigManager.get("jt");
        System.out.println(config.integer("asd.ddd", 1));
    }

}