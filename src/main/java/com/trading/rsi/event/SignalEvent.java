package com.trading.rsi.event;

import com.trading.rsi.model.RsiSignal;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import org.springframework.lang.NonNull;

@Getter
public class SignalEvent extends ApplicationEvent {
    
    private final RsiSignal signal;
    
    public SignalEvent(@NonNull Object source, @NonNull RsiSignal signal) {
        super(source);
        this.signal = signal;
    }
}
