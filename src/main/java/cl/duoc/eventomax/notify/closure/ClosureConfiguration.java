package cl.duoc.eventomax.notify.closure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClosureSettings.class)
@EnableScheduling
public class ClosureConfiguration {}
