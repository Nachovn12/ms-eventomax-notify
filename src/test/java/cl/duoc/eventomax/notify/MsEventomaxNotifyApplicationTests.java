package cl.duoc.eventomax.notify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnableAutoConfiguration(exclude = RabbitAutoConfiguration.class)
class MsEventomaxNotifyApplicationTests {

    @Test
    void contextLoads() {
    }
}
