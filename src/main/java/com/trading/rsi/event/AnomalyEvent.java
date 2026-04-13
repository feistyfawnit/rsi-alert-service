package com.trading.rsi.event;

import com.trading.rsi.model.AnomalyAlert;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import org.springframework.lang.NonNull;

@Getter
public class AnomalyEvent extends ApplicationEvent {

    private final AnomalyAlert alert;

    public AnomalyEvent(@NonNull Object source, @NonNull AnomalyAlert alert) {
        super(source);
        this.alert = alert;
    }
}
