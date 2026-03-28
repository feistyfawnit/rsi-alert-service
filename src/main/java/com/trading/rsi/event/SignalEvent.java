package com.trading.rsi.event;

import com.trading.rsi.model.RsiSignal;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class SignalEvent extends ApplicationEvent {
    
    private final RsiSignal signal;
    
    public SignalEvent(Object source, RsiSignal signal) {
        super(source);
        this.signal = signal;
    }
}
