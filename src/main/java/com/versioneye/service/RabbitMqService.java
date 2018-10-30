package com.versioneye.service;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RabbitMqService {

    static final Logger logger = LogManager.getLogger(RabbitMqService.class.getName());

    public static Connection getConnection(String host, int port) throws Exception{
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        String rabbitmqUser = System.getenv("RM_PORT_USER");
        String rabbitmqCred = System.getenv("RM_PORT_CRED");
        if (rabbitmqUser != null && !rabbitmqUser.isEmpty()) {
            logger.info("Using credentials for RabbitMQ");
            factory.setUsername(rabbitmqUser);
            factory.setPassword(rabbitmqCred);
        }

        return factory.newConnection();
    }

}
