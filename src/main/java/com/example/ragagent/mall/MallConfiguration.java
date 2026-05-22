package com.example.ragagent.mall;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration
@EnableConfigurationProperties({MallProperties.class, MallMcpProperties.class})
public class MallConfiguration {
}
