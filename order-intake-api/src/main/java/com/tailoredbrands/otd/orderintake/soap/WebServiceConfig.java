package com.tailoredbrands.otd.orderintake.soap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

/**
 * Spring WS wiring. Spring Boot's {@code WebServicesAutoConfiguration} registers the
 * {@code MessageDispatcherServlet} at {@code spring.webservices.path} (= {@code /ws}); the bean
 * named {@code orders} is served as {@code /ws/orders.wsdl}.
 */
@Configuration
public class WebServiceConfig {

    public static final String NAMESPACE = "http://tailoredbrands.com/legacy/oms/v1";

    @Bean(name = "orders")
    public DefaultWsdl11Definition ordersWsdl(XsdSchema legacyOrderSchema) {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("OrdersPort");
        wsdl.setServiceName("OrdersService");
        wsdl.setLocationUri("/ws");
        wsdl.setTargetNamespace(NAMESPACE);
        wsdl.setSchema(legacyOrderSchema);
        return wsdl;
    }

    @Bean
    public XsdSchema legacyOrderSchema() {
        return new SimpleXsdSchema(new ClassPathResource("xsd/legacy-order.xsd"));
    }
}
