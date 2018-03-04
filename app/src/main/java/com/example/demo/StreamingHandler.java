package com.example.demo;

import com.example.demo.protocol.IProtocol;
import com.exchange.IPricingClient;
import com.exchange.IPricingListener;
import com.exchange.impl.RandomPriceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class StreamingHandler extends TextWebSocketHandler implements IPricingListener {

    private final Logger log;

    private IPricingClient client;
    private IProtocol proto;
    private Map<String, Set<WebSocketSession>> subscriptions = new HashMap<>();

    public StreamingHandler(IProtocol proto, String logPrefix) {
        this.log = LoggerFactory.getLogger(logPrefix);
        this.proto = proto;
        client = new RandomPriceGenerator();
        client.setListener(this);
        client.start(); // TODO don't do it in constructor, please rewrite
        log.info("created");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession s) throws Exception {
        log.info("WS connection established {}", s.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
        log.info("WS ({}) {}", s.getId(), m.getPayload());

        Map<String, String> request = proto.fromString(m.getPayload());
        String symbol = request.get(IProtocol.SYMBOL);
        boolean isSubscribe = request.get(IProtocol.COMMAND).equals(IProtocol.SUBSCRIBE);

        boolean doSubscribe = false;
        boolean doUnsubscribe = false;

        synchronized (subscriptions) {
            Set<WebSocketSession> sessions = subscriptions.get(symbol);

            if (isSubscribe) {
                if (sessions == null) {
                    sessions = new HashSet<>();
                    subscriptions.put(symbol, sessions);
                }

                sessions.add(s);

                if (sessions.size() == 1) {
                    doSubscribe = true;
                }
            } else {
                if (sessions != null) {
                    sessions.remove(s);
                    if (sessions.size() == 0) {
                        subscriptions.remove(symbol);
                        doUnsubscribe = true;
                    }
                }
            }
        }

        if (doSubscribe) {
            client.subscribe(symbol);
        }

        if (doUnsubscribe) {
            client.unsubscribe(symbol);
        }
    }

    @Override
    public void onData(String symbol, Map<String, String> data) {
        log.debug("SEND {} {}", symbol, data);

        Set<WebSocketSession> sessions = new HashSet<>();
        synchronized (subscriptions) {
            sessions.addAll(subscriptions.get(symbol));
        }

        sessions.forEach((s) -> {
            try {
                data.put(IProtocol.SYMBOL, symbol);
                s.sendMessage(new TextMessage(proto.toString(data)));
            } catch (IOException e) {
                log.error("Failed toString send message", e);
            }
        });
    }

    @Override
    public void handleTransportError(WebSocketSession s, Throwable e) throws Exception {
        log.error("WS error (" + s.getId() + ")", e);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus st) throws Exception {
        log.info("WS connection closed {}, status {}", s.getId(), st);

        synchronized (subscriptions) {
            subscriptions.values().forEach(sessions -> {
                sessions.remove(s);
            });
        }
    }
}