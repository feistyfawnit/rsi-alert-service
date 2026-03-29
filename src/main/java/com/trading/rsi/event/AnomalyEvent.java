package com.trading.rsi.event;

import com.trading.rsi.model.AnomalyAlert;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class AnomalyEvent extends ApplicationEvent {

    private final AnomalyAlert alert;

    public AnomalyEvent(Object source, AnomalyAlert alert) {
        super(source);
        this.alert = alert;
    }
}
